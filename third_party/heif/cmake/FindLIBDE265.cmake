# libheif calls find_package(LIBDE265). Use the pinned target and its generated headers when cross-compiling.
set(LIBDE265_LIBRARIES de265)
set(LIBDE265_FOUND TRUE)
