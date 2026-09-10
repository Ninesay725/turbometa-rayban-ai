package com.smartview.glassai.glasses

import android.graphics.Bitmap

/**
 * android.graphics.Bitmap has no constructor in the android.jar stubs and every factory returns
 * null under isReturnDefaultValues, so JVM tests that only need a *reference* allocate one
 * without running a constructor. Never call any method on the result.
 *
 * sun.misc.Unsafe is reached purely reflectively: the unit-test compile classpath is the mockable
 * android.jar, which has no `sun.misc` package, so naming the type in source does not compile.
 */
object TestBitmaps {
    fun stub(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, Bitmap::class.java) as Bitmap
    }
}
