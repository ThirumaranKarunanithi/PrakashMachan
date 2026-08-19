package com.ledgerintegrity.platform.gst.persist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface GstMatchResultRepository extends JpaRepository<GstMatchResult, Long> {

    /** Pass legacy=true for PURCHASE so pre-side rows (null) are included. */
    @Query("select m from GstMatchResult m where m.engagementId = :id "
            + "and (m.side = :side or (:legacy = true and m.side is null)) "
            + "order by m.taxDiffPaise desc")
    List<GstMatchResult> findBySide(@Param("id") UUID engagementId,
                                    @Param("side") GstMatchResult.Side side,
                                    @Param("legacy") boolean legacy);

    @Query("select m from GstMatchResult m where m.engagementId = :id and m.category = :category "
            + "and (m.side = :side or (:legacy = true and m.side is null)) "
            + "order by m.taxDiffPaise desc")
    List<GstMatchResult> findBySideAndCategory(@Param("id") UUID engagementId,
                                               @Param("side") GstMatchResult.Side side,
                                               @Param("category") GstMatchResult.Category category,
                                               @Param("legacy") boolean legacy);

    @Modifying
    @Query("delete from GstMatchResult m where m.engagementId = :id "
            + "and (m.side = :side or (:legacy = true and m.side is null))")
    void deleteBySide(@Param("id") UUID engagementId,
                      @Param("side") GstMatchResult.Side side,
                      @Param("legacy") boolean legacy);
}
