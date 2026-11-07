# sherpa-onnx: JNI обращается к полям конфигов по именам
-keep class com.k2fsa.sherpa.onnx.** { *; }
# LiteRT-LM: JNI
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *; }
