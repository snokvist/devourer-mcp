package org.openipc.devourer.scratchpad

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * The one JSON configuration for scratchpad programs.
 *
 * Single definition on purpose. The discriminator name is part of the wire
 * format a model writes against, and a test that rebuilt this configuration
 * would be testing a copy — which is exactly how the `kind` collision survived
 * as long as it did. Production and tests decode through the same object.
 */
public object ScratchpadJson {

    /**
     * `kind` names the source type, e.g. `{"kind":"capture.metric"}`.
     *
     * It is the natural word for a model to reach for, which is why it wins
     * over any field that might want the same name. A subclass property called
     * `kind` would silently receive this value instead of the caller's; see
     * [CaptureMetricSource.frameKind].
     */
    public const val DISCRIMINATOR: String = "kind"

    public val module: SerializersModule = SerializersModule {
        polymorphic(Source::class) {
            subclass(CaptureMetricSource::class)
            subclass(HttpPollSource::class)
            subclass(RadioMetricSource::class)
        }
    }

    public val format: Json = Json {
        prettyPrint = true
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
        classDiscriminator = DISCRIMINATOR
        serializersModule = module
    }
}
