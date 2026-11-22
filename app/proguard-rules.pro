# MuPDF uses JNI and looks up Java classes/fields by name from native code.
-keep class com.artifex.mupdf.fitz.** { *; }
-dontwarn com.artifex.mupdf.**
