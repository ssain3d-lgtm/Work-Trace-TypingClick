package dev.worktrace.tiktrace.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RawPayloadDao {

    /** digest 가 이미 있으면 -1 을 돌려준다. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicates(payload: RawPayload): Long

    @Query(
        """
        SELECT COUNT(*)                    AS payloads,
               IFNULL(SUM(item_count), 0)  AS items,
               IFNULL(MAX(captured_at), 0) AS lastAt,
               IFNULL(SUM(byte_size), 0)   AS bytes
        FROM raw_payload
        """
    )
    fun observeStats(): Flow<CaptureStats>

    /**
     * 내보내기용 페이지 조회.
     *
     * OFFSET 이 아니라 keyset 으로 넘긴다 — 내보내는 도중에도 캡처는 계속되므로
     * OFFSET 을 쓰면 새 행이 끼어들면서 건너뛰거나 중복된다.
     * 본문이 수백 KB 라 한 페이지를 작게 유지해야 메모리가 터지지 않는다.
     */
    @Query("SELECT * FROM raw_payload WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun pageAfter(afterId: Long, limit: Int): List<RawPayload>
}
