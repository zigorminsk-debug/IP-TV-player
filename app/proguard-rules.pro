# Minification is currently disabled (see app/build.gradle.kts).
# Rules below are kept for the future R8 enablement:
# - kotlinx.serialization keeps generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Media3 / OkHttp ship consumer rules; nothing extra required.
