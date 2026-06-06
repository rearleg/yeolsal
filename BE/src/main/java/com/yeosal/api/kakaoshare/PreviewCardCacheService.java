package com.yeosal.api.kakaoshare;

import com.fasterxml.jackson.databind.JsonNode;
import com.yeosal.api.common.ServiceUnavailableException;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.survival.RoomRuleVersion;
import com.yeosal.api.survival.RoomRuleVersionRepository;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates render-or-serve semantics for the KakaoTalk invite preview
 * card. Single-flight is enforced by Postgres
 * {@code pg_try_advisory_xact_lock(hashtext('preview_card'), room_id)} so two
 * concurrent cold renders for the same room never duplicate disk writes.
 *
 * <p>The cache row + the file on disk are written in a fixed order: PNG file
 * first (atomic move from a temp path), then DB row. That ordering guarantees
 * a downstream {@code GET /preview-card} that races with the render either
 * sees no row (cold-miss path) or sees a row whose {@code png_url} already
 * resolves to a valid file. The reverse order would risk a 404 from nginx
 * for a brief window — see story trap #10.
 */
@Service
public class PreviewCardCacheService {

    private static final Logger log = LoggerFactory.getLogger(PreviewCardCacheService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** Story 6.1 AC3 — server-side stale judgement. Distinct from the
     *  client-side {@code Cache-Control: max-age} (story trap #6). */
    static final Duration CACHE_TTL = Duration.ofHours(1);

    private final PreviewCardCacheRepository cacheRepository;
    private final RoomRepository rooms;
    private final RoomMemberRepository roomMembers;
    private final RoomRuleVersionRepository ruleVersions;
    private final InvitePreviewRenderer renderer;
    private final PngRasterizer rasterizer;
    private final PreviewCardBackgroundRenderer backgroundRenderer;
    private final EntityManager em;
    private final Clock clock;
    private final Path pngOutputDir;
    private final String previewCardBase;

    public PreviewCardCacheService(
            PreviewCardCacheRepository cacheRepository,
            RoomRepository rooms,
            RoomMemberRepository roomMembers,
            RoomRuleVersionRepository ruleVersions,
            InvitePreviewRenderer renderer,
            PngRasterizer rasterizer,
            // @Lazy breaks the circular dependency:
            // PreviewCardBackgroundRenderer constructor-injects this service
            // (so @Async dispatch crosses a real Spring AOP proxy boundary
            // for trap #3), and this service needs the renderer to kick
            // background work on the stale path. The lazy proxy is created
            // up-front and resolved on first call — by then both beans exist.
            @Lazy PreviewCardBackgroundRenderer backgroundRenderer,
            EntityManager em,
            Clock clock,
            @Value("${yeosal.share.preview-cards-dir:/var/yeosal/preview-cards}") String pngOutputDir,
            @Value("${yeosal.share.preview-card-base:https://api.rearleg.com/yeolsal}") String previewCardBase) {
        this.cacheRepository = cacheRepository;
        this.rooms = rooms;
        this.roomMembers = roomMembers;
        this.ruleVersions = ruleVersions;
        this.renderer = renderer;
        this.rasterizer = rasterizer;
        this.backgroundRenderer = backgroundRenderer;
        this.em = em;
        this.clock = clock;
        this.pngOutputDir = Path.of(pngOutputDir);
        this.previewCardBase = stripTrailingSlash(previewCardBase);
    }

    /**
     * Returns the PNG URL to serve. Stale-while-regenerate semantics — see
     * the class doc for the cold/stale/fresh dispatch.
     */
    @Transactional
    public Optional<String> resolve(long roomId) {
        Room room = rooms.findById(roomId).orElse(null);
        if (room == null) return Optional.empty();

        Optional<PreviewCardCache> existing = cacheRepository.findById(roomId);
        if (existing.isPresent()) {
            PreviewCardCache cache = existing.get();
            boolean stale = cache.getRenderedAt().plus(CACHE_TTL).isBefore(clock.instant());
            if (stale) {
                backgroundRenderer.render(room);
            }
            return Optional.of(cache.getPngUrl());
        }
        return Optional.of(synchronousRender(room));
    }

    /**
     * Invalidate hook used by the rule / member-count change call sites.
     * Idempotent — deleting a non-existent row is a no-op.
     */
    @Transactional
    public void invalidate(long roomId) {
        try {
            cacheRepository.deleteById(roomId);
        } catch (RuntimeException ex) {
            // Concurrent invalidate vs. delete-cascade can race; the next
            // resolve() rebuilds anyway, so the failure is informational.
            log.warn("[kakaoshare] cache invalidate failed roomId={}: {}",
                    roomId, ex.toString());
        }
    }

    /** Called from {@link PreviewCardBackgroundRenderer} only — crosses the
     *  Spring AOP proxy boundary so {@code @Async} actually dispatches. */
    @Transactional
    public void backgroundRenderUnchecked(Room room) {
        if (!tryAcquireLock(room.getId())) {
            return;
        }
        try {
            doRender(room);
        } catch (RuntimeException ex) {
            log.warn("[kakaoshare] background render failed roomId={}: {}",
                    room.getId(), ex.toString());
        }
    }

    private String synchronousRender(Room room) {
        if (!tryAcquireLock(room.getId())) {
            return cacheRepository.findById(room.getId())
                    .map(PreviewCardCache::getPngUrl)
                    .orElseThrow(() -> new ServiceUnavailableException(
                            "preview-card render in flight; retry shortly"));
        }
        return doRender(room);
    }

    private String doRender(Room room) {
        long memberCount = roomMembers.countByRoom(room);
        YearMonth currentMonth = currentMonthKST();
        RoomRuleVersion currentRule = ruleVersions
                .findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(
                        room.getId(), currentMonth.toString())
                .orElseThrow(() -> new PreviewCardRenderException(
                        "current rule version missing for roomId=" + room.getId()));
        JsonNode payload = currentRule.getRulePayload();
        String preset = payload.path("preset").asText("DAILY_UPDATE");
        boolean weekendInclude = payload.path("weekendInclude").asBoolean(false);

        String svg = renderer.render(room, (int) memberCount, preset, weekendInclude);
        byte[] pngBytes = rasterizer.toPng(svg);

        writePngAtomically(room.getId(), pngBytes);
        String pngUrl = previewCardBase + "/preview-cards/" + room.getId() + ".png";

        PreviewCardCache cache = cacheRepository.findById(room.getId())
                .orElseGet(() -> new PreviewCardCache(
                        room.getId(), pngUrl, clock.instant(),
                        currentRule.getId(), (int) memberCount));
        cache.setPngUrl(pngUrl);
        cache.setRenderedAt(clock.instant());
        cache.setRuleVersionId(currentRule.getId());
        cache.setMemberCountAtRender((int) memberCount);
        cacheRepository.save(cache);
        return pngUrl;
    }

    private void writePngAtomically(long roomId, byte[] pngBytes) {
        try {
            Files.createDirectories(pngOutputDir);
            Path target = pngOutputDir.resolve(roomId + ".png");
            Path temp = pngOutputDir.resolve(roomId + ".png.tmp");
            Files.write(temp, pngBytes);
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (UnsupportedOperationException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new PreviewCardRenderException(
                    "preview-card PNG write failed roomId=" + roomId, ex);
        }
    }

    private boolean tryAcquireLock(long roomId) {
        Object acquired = em.createNativeQuery(
                "SELECT pg_try_advisory_xact_lock("
                        + "hashtext('preview_card'), CAST(:rid AS int4))")
                .setParameter("rid", (int) (roomId & 0x7FFF_FFFFL))
                .getSingleResult();
        return Boolean.TRUE.equals(acquired);
    }

    private YearMonth currentMonthKST() {
        return YearMonth.from(LocalDate.ofInstant(clock.instant(), KST));
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
