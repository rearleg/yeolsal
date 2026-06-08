package com.yeosal.api.ceremony;

import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.kakaoshare.PngRasterizer;
import com.yeosal.api.kakaoshare.PreviewCardRenderException;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.chat.ChatService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates Final-3 poster generation for a single (roomId, yearMonth).
 * Story 7.2's scheduled job iterates rooms and calls
 * {@link #generatePoster}; Story 7.3's Home tab consumes the row via
 * {@link #getPosterForMember} (membership-gated read).
 *
 * <p>Reuses {@code kakaoshare/PngRasterizer} cross-module (Story 6.1
 * precedent — same 800 × 420 Kakao-share-thumbnail aspect, same warm-up
 * cost). No new Batik dependency, no new migration (V11 (10) already
 * ships the table).
 */
@Service
public class FinalThreeService {

    private static final Logger log = LoggerFactory.getLogger(FinalThreeService.class);

    private final FinalThreePosterRepository posterRepository;
    private final RoomRepository rooms;
    private final RoomMemberRepository roomMembers;
    private final SvgRenderer svgRenderer;
    private final PngRasterizer pngRasterizer;
    private final ChatService chatService;
    private final EntityManager em;
    private final Path pngOutputDir;
    private final String posterUrlBase;

    public FinalThreeService(
            FinalThreePosterRepository posterRepository,
            RoomRepository rooms,
            RoomMemberRepository roomMembers,
            SvgRenderer svgRenderer,
            PngRasterizer pngRasterizer,
            ChatService chatService,
            EntityManager em,
            @Value("${yeosal.share.posters-dir:/var/yeosal/posters}") String pngOutputDir,
            @Value("${yeosal.share.preview-card-base:https://api.rearleg.com/yeolsal}") String posterUrlBase) {
        this.posterRepository = posterRepository;
        this.rooms = rooms;
        this.roomMembers = roomMembers;
        this.svgRenderer = svgRenderer;
        this.pngRasterizer = pngRasterizer;
        this.chatService = chatService;
        this.em = em;
        this.pngOutputDir = Path.of(pngOutputDir);
        this.posterUrlBase = stripTrailingSlash(posterUrlBase);
    }

    /**
     * Renders + persists the Final-3 poster for a single room + month, or
     * publishes the zero-survivor chat fallback if no member is ACTIVE.
     *
     * <p>Idempotency: if a {@code final_three_posters} row already exists
     * for {@code (roomId, yearMonth)}, this method short-circuits with the
     * existing poster (no re-render, no re-publish). Posters are immutable
     * per FR-8.7.6.
     *
     * <p>Zero-survivor caveat: this method is NOT idempotent on the
     * zero-survivor path — a second call publishes a second chat row.
     * Story 7.2's batch job pre-filters rooms with at least one ACTIVE
     * survivor before invoking this, so duplicate fallback messages
     * cannot occur in steady-state.
     *
     * @return {@link Optional#empty()} on the zero-survivor path (chat
     *         fallback published, no poster). Present otherwise.
     */
    @Transactional
    public Optional<FinalThreePoster> generatePoster(long roomId, YearMonth yearMonth) {
        return generatePosterWithResult(roomId, yearMonth).poster();
    }

    @Transactional
    public GenerationResult generatePosterWithResult(long roomId, YearMonth yearMonth) {
        FinalThreePosterId id = new FinalThreePosterId(roomId, yearMonth.toString());
        Optional<FinalThreePoster> existing = posterRepository.findById(id);
        if (existing.isPresent()) {
            return new GenerationResult(existing, false);
        }

        acquireGenerationLock(roomId, yearMonth);
        existing = posterRepository.findById(id);
        if (existing.isPresent()) {
            return new GenerationResult(existing, false);
        }

        Room room = rooms.findById(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));

        List<SurvivorTenureRow> survivors = querySurvivors(roomId);
        if (survivors.isEmpty()) {
            chatService.publishMonthlyNoSurvivorsSystemMessage(roomId, yearMonth);
            return new GenerationResult(Optional.empty(), false);
        }

        String svg = svgRenderer.render(room, yearMonth, survivors, survivors.size());

        byte[] pngBytes = null;
        try {
            pngBytes = pngRasterizer.toPng(svg);
        } catch (PreviewCardRenderException ex) {
            log.warn("[ceremony] PNG rasterize failed roomId={} yearMonth={}; persisting SVG only",
                    roomId, yearMonth, ex);
        }

        String pngUrl = pngBytes != null ? writePngAtomically(roomId, yearMonth, pngBytes) : null;

        FinalThreePoster poster = new FinalThreePoster(
                roomId, yearMonth.toString(), svg, pngUrl);
        return new GenerationResult(Optional.of(posterRepository.save(poster)), true);
    }

    public record GenerationResult(Optional<FinalThreePoster> poster, boolean created) {}

    /**
     * Story 7.2 — pre-existence check used by {@link FinalThreeJob} to
     * decide whether the realtime publish should fire. {@code true} =
     * poster row already exists (idempotent rerun, no publish).
     * {@code false} = first generation (publish after generate).
     * Read-only; does NOT acquire the advisory lock.
     */
    @Transactional(readOnly = true)
    public boolean existsPoster(long roomId, YearMonth yearMonth) {
        return posterRepository.existsById(
                new FinalThreePosterId(roomId, yearMonth.toString()));
    }

    /**
     * Story 7.3 read path. Membership-gated lookup that throws
     * {@link ForbiddenException} for non-members and
     * {@link PosterNotFoundException} when no poster row exists for the
     * given (roomId, yearMonth). Mirrors the {@code SurvivalStateService.roster}
     * privacy stance: "room exists but you can't see it" trumps
     * "room doesn't exist."
     */
    @Transactional(readOnly = true)
    public FinalThreePoster getPosterForMember(long roomId, YearMonth yearMonth, long viewerUserId) {
        if (!roomMembers.existsByRoomIdAndUserId(roomId, viewerUserId)) {
            throw new ForbiddenException("방 멤버만 접근할 수 있습니다.");
        }
        FinalThreePosterId id = new FinalThreePosterId(roomId, yearMonth.toString());
        return posterRepository.findById(id)
                .orElseThrow(() -> new PosterNotFoundException(roomId, yearMonth));
    }

    /**
     * Native survivors query — top-tenured first. Picks ACTIVE survival_state
     * rows joined to room_members + users, ordered by {@code joined_at ASC,
     * user_id ASC} so two members joined at the same instant get a
     * deterministic order across reruns.
     */
    List<SurvivorTenureRow> querySurvivors(long roomId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT u.nickname AS nickname,
                       u.id       AS user_id,
                       rm.joined_at AS joined_at
                  FROM survival_state ss
                  JOIN room_members  rm ON rm.room_id = ss.room_id AND rm.user_id = ss.user_id
                  JOIN users         u  ON u.id       = ss.user_id
                 WHERE ss.room_id = :rid
                   AND ss.status  = 'ACTIVE'
                 ORDER BY rm.joined_at ASC, u.id ASC
                """, Tuple.class)
                .setParameter("rid", roomId)
                .getResultList();

        return rows.stream()
                .map(t -> new SurvivorTenureRow(
                        t.get("nickname", String.class),
                        ((Number) t.get("user_id")).longValue(),
                        ((java.sql.Timestamp) t.get("joined_at")).toInstant()))
                .toList();
    }

    private String writePngAtomically(long roomId, YearMonth yearMonth, byte[] pngBytes) {
        try {
            Files.createDirectories(pngOutputDir);
            String fileName = roomId + "-" + yearMonth + ".png";
            Path target = pngOutputDir.resolve(fileName);
            Path temp = pngOutputDir.resolve(fileName + "." + UUID.randomUUID() + ".tmp");
            Files.write(temp, pngBytes);
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (UnsupportedOperationException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return posterUrlBase + "/posters/" + fileName;
        } catch (IOException ex) {
            log.warn("[ceremony] poster PNG write failed roomId={} yearMonth={}; svg-only fallback",
                    roomId, yearMonth, ex);
            return null;
        }
    }

    private void acquireGenerationLock(long roomId, YearMonth yearMonth) {
        String key = "final_three_poster:" + roomId + ":" + yearMonth;
        em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:k, 0))")
                .setParameter("k", key)
                .getSingleResult();
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
