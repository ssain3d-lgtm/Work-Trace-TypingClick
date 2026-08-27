package dev.worktrace.tiktrace

import android.app.Application
import dev.worktrace.tiktrace.data.CaptureDatabase
import dev.worktrace.tiktrace.data.CaptureRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    /**
     * 프로세스 수명과 같이 가는 스코프.
     * 캡처는 액티비티 생명주기와 무관하게 끝까지 적재돼야 한다 — 화면을 벗어나는
     * 순간 인플라이트 응답이 버려지면 그 관측은 영원히 복구되지 않는다.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository: CaptureRepository by lazy {
        CaptureRepository(CaptureDatabase.get(this).rawPayloads(), applicationScope)
    }
}
