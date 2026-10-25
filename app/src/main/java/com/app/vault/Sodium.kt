package com.app.vault

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/**
 * The one libsodium binding this app creates.
 *
 * `SlotArray` and `BlobStore` each held their **own** `LazySodiumAndroid`, so
 * every instance registered a fresh set of JNA/JNI global references and never
 * released them. Nothing noticed while each type was constructed a handful of
 * times.
 *
 * P1-8.6's suite crossed the limit and the runtime aborted:
 *
 *     JNI ERROR (app bug): global reference table overflow (max=51200)
 *
 * It is a real leak, not a test artifact. The app constructs a `BlobStore` per
 * worker run and a `SlotArray` per unlock attempt, so a long-lived process
 * accumulates them at exactly the same rate — it simply takes longer to reach
 * 51 200 than a test suite does.
 *
 * The binding is stateless and thread-safe, so one is all that was ever needed.
 */
internal object Sodium {
    val lazy: LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())
}
