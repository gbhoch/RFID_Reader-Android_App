# A biblioteca da TSL usa reflexao para montar/parsear comandos ASCII.
-keep class com.uk.tsl.** { *; }
-dontwarn com.uk.tsl.**

# Retrofit / Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.megagoglio.rfidinventory.data.remote.** { *; }
