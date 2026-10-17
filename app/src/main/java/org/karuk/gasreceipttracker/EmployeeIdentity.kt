package org.karuk.gasreceipttracker
import android.graphics.Bitmap
import android.graphics.Color
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import android.util.Base64

/** QR contains public identity only. Private key stays in Android Keystore. */
object EmployeeIdentity {
    private const val ALIAS="grt_employee_identity_v1"
    fun publicKey():ByteArray {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        if(!store.containsAlias(ALIAS)){
            val generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1")).build())
            generator.generateKeyPair()
        }
        return store.getCertificate(ALIAS).publicKey.encoded
    }
    fun fingerprint():String=MessageDigest.getInstance("SHA-256").digest(publicKey())
        .joinToString(""){"%02X".format(it)}.chunked(4).take(8).joinToString("-")
    fun payload(name:String,email:String):String {
        require(name.isNotBlank()&&android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()){"Enter name and valid email first"}
        return JSONObject().put("format","karuk-employee-pair-v1").put("name",name.trim()).put("email",email.trim())
            .put("publicKey",Base64.encodeToString(publicKey(),Base64.NO_WRAP)).put("fingerprint",fingerprint()).toString()
    }
    fun qr(payload:String):Bitmap {
        val bits=MultiFormatWriter().encode(payload,BarcodeFormat.QR_CODE,800,800)
        return Bitmap.createBitmap(800,800,Bitmap.Config.ARGB_8888).apply{
            for(y in 0 until 800)for(x in 0 until 800)setPixel(x,y,if(bits[x,y])Color.BLACK else Color.WHITE)
        }
    }
}
