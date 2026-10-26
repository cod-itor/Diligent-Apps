package com.ditarector.diligent.sync.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The platforms a device can report (SPEC A.1), with their wire names. */
@Serializable
public enum class Platform(public val wireName: String) {
    @SerialName("android") ANDROID("android"),
    @SerialName("ios") IOS("ios"),
    @SerialName("windows") WINDOWS("windows"),
    @SerialName("macos") MACOS("macos"),
    @SerialName("linux") LINUX("linux"),
    ;

    public companion object {
        /** The platform with [wireName], or null for a value this version does not know. */
        public fun fromWireName(wireName: String): Platform? = entries.firstOrNull { it.wireName == wireName }
    }
}
