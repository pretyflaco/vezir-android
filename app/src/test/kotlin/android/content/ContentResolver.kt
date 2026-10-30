package android.content

import android.net.Uri
import java.io.OutputStream

abstract class ContentResolver(context: Context?) {
    open fun update(
        uri: Uri,
        values: ContentValues,
        where: String?,
        selectionArgs: Array<String>?,
    ): Int = throw UnsupportedOperationException()

    open fun openOutputStream(uri: Uri, mode: String): OutputStream = throw UnsupportedOperationException()
}
