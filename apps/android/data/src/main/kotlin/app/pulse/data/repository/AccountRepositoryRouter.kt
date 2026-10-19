package app.pulse.data.repository

import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.PulseWave8Logic
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * A stable DI handle, not a mutable database. Each invocation captures one whole
 * repository (API, database, DAOs and session); a suspension cannot move its
 * continuation into another account. Observations cancel/rebind on switching.
 *
 * The interface has hundreds of operations. Intercepting its boundary avoids
 * hundreds of hand-maintained forwarding methods that could miss a new API.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AccountRepositoryRouter(
    initial: PulseRepository,
    private val startAccount: (String) -> PulseRepository,
    private val stopAccount: () -> PulseRepository,
) {
    private val active = MutableStateFlow(initial)
    private val transitionLock = Any()

    @Suppress("UNCHECKED_CAST")
    val repository: PulseRepository = Proxy.newProxyInstance(
        PulseRepository::class.java.classLoader, arrayOf(PulseRepository::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "AccountScopedPulseRepository"
            "start" -> synchronized(transitionLock) { active.value = startAccount(args!![0] as String); null }
            "stop" -> synchronized(transitionLock) { active.value = stopAccount(); null }
            else -> if (method.returnType == Flow::class.java) {
                flow {
                    var initial = true
                    emitAll(active.flatMapLatest { target ->
                        flow<Any?> {
                            // Initial first() consumers must see actual storage,
                            // not a synthetic null that would erase restored drafts.
                            if (!initial) when (method.name) {
                                "events" -> Unit
                                "observeConnected" -> emit(false)
                                "observePresence" -> emit(emptySet<String>())
                                "observeDraft" -> emit(null)
                                "observeDrafts", "getConvThemes" -> emit(emptyMap<String, Any>())
                                "getPulsePrefs" -> emit(PulseWave8Logic.mergePrefs(null))
                                else -> emit(emptyList<Any>())
                            }
                            initial = false
                            emitAll(invoke(target, method, args) as Flow<Any?>)
                        }
                    })
                }
            } else {
                val target = active.value
                invoke(target, method, args)
            }
        }
    } as PulseRepository

    private fun invoke(target: PulseRepository, method: Method, args: Array<out Any?>?): Any? =
        try { method.invoke(target, *(args ?: emptyArray())) }
        catch (error: InvocationTargetException) { throw error.targetException }
}
