/*
 * Source++, the open-source live coding platform.
 * Copyright (C) 2022 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package spp.platform.storage

import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.shareddata.AsyncMap
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.TimeUnit

class ExpiringSharedData<K, V> private constructor(
    vertx: Vertx,
    private val expireAfterWriteNanos: Long = -1,
    private val expireAfterAccessNanos: Long = -1,
    private val backingMap: AsyncMap<K, V>,
    private val expirationMap: AsyncMap<K, Long>
) {

    companion object {
        private val log = KotlinLogging.logger {}

        fun newBuilder(): Builder {
            return Builder()
        }
    }

    suspend fun getIfPresent(key: K): V? {
        cleanup()

        val promise = Promise.promise<V?>()
        backingMap.get(key) { result ->
            if (result.succeeded()) {
                val value = result.result()
                if (value != null) {
                    if (expireAfterAccessNanos > 0) {
                        expirationMap.put(key, System.nanoTime()).onSuccess {
                            promise.complete(value)
                        }.onFailure {
                            promise.fail(it)
                        }
                    } else {
                        promise.complete(value)
                    }
                } else {
                    promise.complete(null)
                }
            } else {
                promise.fail(result.cause())
            }
        }
        return promise.future().await()
    }

    suspend fun put(key: K, value: V) {
        cleanup()

        val promise = Promise.promise<Void>()
        backingMap.put(key, value) { result ->
            if (result.succeeded()) {
                if (expireAfterWriteNanos > 0) {
                    expirationMap.put(key, System.nanoTime()).onSuccess {
                        promise.complete()
                    }.onFailure {
                        promise.fail(it)
                    }
                } else {
                    promise.complete()
                }
            } else {
                promise.fail(result.cause())
            }
        }
        promise.future().await()
    }

    init {
        vertx.setPeriodic(5000) {
            GlobalScope.launch(vertx.dispatcher()) {
                cleanup()
            }
        }
    }

    private suspend fun cleanup() {
        val promise = Promise.promise<List<K>>()
        val now = System.nanoTime()
        expirationMap.entries().onSuccess { entries ->
            val expiredKeys = entries.filter { entry ->
                val expiration = entry.value
                if (expireAfterWriteNanos > 0) {
                    now - expiration > expireAfterWriteNanos
                } else {
                    now - expiration > expireAfterAccessNanos
                }
            }.map { it.key }
            promise.complete(expiredKeys)
        }.onFailure {
            log.error(it) { "Failed to get entries from expiration map" }
            promise.fail(it)
        }

        val expiredKeys = promise.future().await()
        expiredKeys.forEach {
            backingMap.remove(it).await()
            expirationMap.remove(it).await()
        }
    }

    suspend fun compute(key: K, function: (K, V?) -> V) {
        cleanup()

        val promise = Promise.promise<Void>()
        backingMap.get(key).onSuccess {
            val value = function(key, it)
            backingMap.put(key, value).onSuccess {
                if (expireAfterWriteNanos > 0) {
                    expirationMap.put(key, System.nanoTime()).onSuccess {
                        promise.complete()
                    }.onFailure {
                        promise.fail(it)
                    }
                } else {
                    promise.complete()
                }
            }.onFailure {
                promise.fail(it)
            }
        }.onFailure {
            promise.fail(it)
        }
        promise.future().await()
    }

    class Builder {
        private var expireAfterWriteNanos: Long = -1
        private var expireAfterAccessNanos: Long = -1

        fun expireAfterWrite(duration: Long, unit: TimeUnit) = apply {
            expireAfterWriteNanos = unit.toNanos(duration)
        }

        fun expireAfterAccess(duration: Long, unit: TimeUnit) = apply {
            expireAfterAccessNanos = unit.toNanos(duration)
        }

        suspend fun <K, V> build(mapId: String, vertx: Vertx, storage: CoreStorage): ExpiringSharedData<K, V> {
            val backingMap = storage.map<K, V>("expiring_shared_data:$mapId:backing_map")
            val expirationMap = storage.map<K, Long>("expiring_shared_data:$mapId:expiration_map")
            return ExpiringSharedData(vertx, expireAfterWriteNanos, expireAfterAccessNanos, backingMap, expirationMap)
        }
    }
}
