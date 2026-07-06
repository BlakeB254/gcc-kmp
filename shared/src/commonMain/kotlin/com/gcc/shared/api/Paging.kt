package com.gcc.shared.api

/**
 * Fetch every page of a paginated endpoint by requesting successive pages
 * until one comes back shorter than [pageSize]. [maxPages] bounds the loop
 * against a server that ignores its limit parameter.
 */
suspend fun <T> fetchAllPages(
    pageSize: Int = 100,
    maxPages: Int = 50,
    fetchPage: suspend (page: Int, limit: Int) -> List<T>,
): List<T> {
    val all = mutableListOf<T>()
    for (page in 1..maxPages) {
        val batch = fetchPage(page, pageSize)
        all += batch
        if (batch.size < pageSize) break
    }
    return all
}
