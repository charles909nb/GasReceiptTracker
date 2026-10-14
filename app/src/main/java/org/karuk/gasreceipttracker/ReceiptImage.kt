package org.karuk.gasreceipttracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlin.math.max

/** Loads a bounded, visually upright bitmap without editing the saved original. */
object ReceiptImage {
    fun loadUpright(context:Context,uri:Uri,maxSide:Int=2600):Bitmap? {
        val orientation=runCatching {
            context.contentResolver.openInputStream(uri).use{stream->
                if(stream==null)ExifInterface.ORIENTATION_NORMAL else
                    ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        context.contentResolver.openInputStream(uri).use{stream->
            if(stream==null)return null
            BitmapFactory.decodeStream(stream,null,bounds)
        }
        if(bounds.outWidth<=0 || bounds.outHeight<=0)return null
        var sample=1
        while(max(bounds.outWidth,bounds.outHeight)/sample>maxSide) sample*=2
        val options=BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}
        val image=context.contentResolver.openInputStream(uri).use{stream->
            if(stream==null)return null
            BitmapFactory.decodeStream(stream,null,options)
        }?:return null
        val matrix=Matrix()
        when(orientation){
            ExifInterface.ORIENTATION_ROTATE_90->matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180->matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270->matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL->matrix.postScale(-1f,1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL->matrix.postScale(1f,-1f)
            ExifInterface.ORIENTATION_TRANSPOSE->{matrix.postRotate(90f);matrix.postScale(-1f,1f)}
            ExifInterface.ORIENTATION_TRANSVERSE->{matrix.postRotate(270f);matrix.postScale(-1f,1f)}
        }
        if(matrix.isIdentity)return image
        return try{
            Bitmap.createBitmap(image,0,0,image.width,image.height,matrix,true).also{
                if(it!==image)image.recycle()
            }
        }catch(e:Exception){image.recycle();throw e}
    }
}
