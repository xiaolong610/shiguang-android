package app.shiguang.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class LibraryRevision(val sources:Long=0,val library:Long=0)
/** Process-wide invalidation also reaches foreground screens when a worker/service writes. */
object LibraryEvents {
    val revision=MutableStateFlow(LibraryRevision())
    fun changed(sources:Boolean=false,library:Boolean=false){revision.update{it.copy(sources=it.sources+if(sources)1 else 0,library=it.library+if(library)1 else 0)}}
}
