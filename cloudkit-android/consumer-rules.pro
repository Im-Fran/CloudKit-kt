# kotlinx.serialization keeps generated serializers for CloudKit models.
-keepclassmembers class io.github.imfran.cloudkit.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.imfran.cloudkit.**$$serializer { *; }
