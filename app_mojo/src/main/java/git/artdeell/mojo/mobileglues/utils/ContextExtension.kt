package git.artdeell.mojo.mobileglues.utils
import git.artdeell.mojo.R

import android.content.Context
import android.widget.Toast

fun Context.toast(text: Any, duration: Int = Toast.LENGTH_SHORT) =
    Toast.makeText(this, text.toString(), duration).show()
