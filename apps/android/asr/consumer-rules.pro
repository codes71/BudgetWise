# ONNX Runtime's JNI code looks its Java classes, fields and constructors up by name. The AAR's own rules only keep
# its telemetry classes.
-keep class ai.onnxruntime.** { *; }
