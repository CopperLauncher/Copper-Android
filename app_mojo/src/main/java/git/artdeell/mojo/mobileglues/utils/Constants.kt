package git.artdeell.mojo.mobileglues.utils
import git.artdeell.mojo.R

import git.artdeell.mojo.Tools

object Constants {
    const val CONFIG_FILE_NAME: String = "config.json"

    val MG_DIRECTORY: String = "${Tools.DIR_DATA}/MobileGlues"

    val CONFIG_FILE_PATH: String = "$MG_DIRECTORY/$CONFIG_FILE_NAME"

    val GLSL_CACHE_FILE_PATH: String = "$MG_DIRECTORY/glsl_cache.tmp"
}
