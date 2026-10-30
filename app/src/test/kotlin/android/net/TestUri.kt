package android.net

import android.os.Parcel
import java.io.File

class TestUri(private val raw: String) : Uri() {
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = false
    override fun getScheme(): String = raw.substringBefore(':')
    override fun getSchemeSpecificPart(): String = raw.substringAfter(':')
    override fun getEncodedSchemeSpecificPart(): String = schemeSpecificPart
    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String? = null
    override fun getPort(): Int = -1
    override fun getPath(): String = if (scheme == "file") File(raw.removePrefix("file://")).path else raw
    override fun getEncodedPath(): String = path
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): List<String> = path.split('/').filter(String::isNotEmpty)
    override fun getLastPathSegment(): String = path.substringAfterLast('/')
    override fun toString(): String = raw
    override fun describeContents(): Int = 0
    override fun writeToParcel(destination: Parcel, flags: Int) = destination.writeString(raw)
    override fun buildUpon(): Uri.Builder = throw UnsupportedOperationException()
}
