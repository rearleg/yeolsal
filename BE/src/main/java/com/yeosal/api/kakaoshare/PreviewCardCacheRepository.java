package com.yeosal.api.kakaoshare;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The cache service only needs {@code findById} + {@code save} + {@code deleteById}
 * — no custom queries. Inheriting {@link JpaRepository} keeps the surface area
 * minimal and matches the room-scoped PK shape.
 */
public interface PreviewCardCacheRepository extends JpaRepository<PreviewCardCache, Long> {
}
