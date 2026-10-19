# raj_jni looks these up by name through JNI.
-keep class app.budgetwise.asr.engine.EngineOutput { <init>(java.lang.String, java.lang.String, int, long, long); }
-keep class app.budgetwise.asr.engine.RajNative { native <methods>; }
