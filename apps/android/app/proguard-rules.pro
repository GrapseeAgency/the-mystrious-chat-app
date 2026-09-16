# ─────────────────────────────────────────────────────────────
# Pulse R8 / ProGuard rules — Wave 8 (spec §7 "R8 after keep-rules
# proven"). Posture: CONSERVATIVE.
#   • ALL app code is kept (shrinking still strips unused library
#     code/resources; obfuscation renames only library classes).
#     This removes the reflective-decode risk surface while still
#     delivering the R8 pipeline the spec asks for.
# ─────────────────────────────────────────────────────────────

# Every app class: DTOs with tolerant JSON decode, Hilt graph, Room
# entities/DAOs, Compose — kept whole.
-keep class app.pulse.** { *; }

# kotlinx.serialization (tolerant PulseJson decoders rely on generated
# companions; ship the upstream-recommended rules verbatim)
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, RuntimeVisibleTypeAnnotations
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.pulse.***$$serializer { *; }
-keepclassmembers class app.pulse.** { *** Companion; }
-keepclasseswithmembers class app.pulse.** { kotlinx.serialization.KSerializer serializer(...); }

# Socket.IO java client (reflective handshake plumbing)
-keep class io.socket.** { *; }
-dontwarn io.socket.**

# Ktor + coroutines ship consumer rules; silence known optional deps
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Standard enum guard (Room TypeConverters use valueOf)
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }

# WebView/JS fallbacks not used, but keep Parcelable/Serializable AAs intact
-keepclassmembers class * implements android.os.Parcelable { public static final android.os.Parcelable$Creator CREATOR; }
-keepclassmembers class * implements java.io.Serializable { static final long serialVersionUID; }
