package com.forge.core.sync

import com.forge.core.model.PRRecord

/**
 * Record baselines for the watch, which can finish several sessions before
 * the phone sends refreshed plans. A session's PRs are judged against the
 * phone's records and the ones the watch earned since, so a 70 kg session
 * followed by a 65 kg one, both offline, doesn't celebrate the 65 too.
 */
object PersonalRecords {

    /**
     * The best record per exercise and type across [records]. Only the best
     * one matters to [com.forge.core.training.PrDetector], so the watch keeps
     * just these: records earned on the watch stay true after the phone
     * catches up, and never need clearing.
     */
    fun best(records: List<PRRecord>): List<PRRecord> =
        records
            .groupBy { it.exerciseId to it.type }
            .values
            .map { group -> group.maxBy { it.value } }
            .sortedWith(compareBy<PRRecord> { it.exerciseId }.thenBy { it.type })
}
