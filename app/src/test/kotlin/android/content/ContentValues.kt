package android.content

import android.os.Parcel
import android.os.Parcelable

class ContentValues : Parcelable {
    private val values = mutableMapOf<String, Int?>()

    fun put(key: String, value: Int?) {
        values[key] = value
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(destination: Parcel, flags: Int) {
        throw UnsupportedOperationException()
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ContentValues> = object : Parcelable.Creator<ContentValues> {
            override fun createFromParcel(source: Parcel): ContentValues = throw UnsupportedOperationException()
            override fun newArray(size: Int): Array<ContentValues?> = arrayOfNulls(size)
        }
    }
}
