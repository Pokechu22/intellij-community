// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.github.util

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import git4idea.DialogManager
import org.jetbrains.annotations.CalledInAwt
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.api.GithubApiRequests
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.authentication.accounts.GithubAccountManager
import org.jetbrains.plugins.github.authentication.ui.GithubLoginDialog
import java.awt.Component
import java.io.IOException

internal const val GITHUB_SETTINGS_PASSWORD_KEY = "GITHUB_SETTINGS_PASSWORD_KEY"

/**
 * Temporary helper
 * Will move single-account authorization data to accounts list if it was a token-based auth and clear old settings
 */
@Suppress("DEPRECATION")
class GithubAccountsMigrationHelper internal constructor(private val settings: GithubSettings,
                                                         private val passwordSafe: PasswordSafe,
                                                         private val accountManager: GithubAccountManager,
                                                         private val executorFactory: GithubApiRequestExecutor.Factory) {
  private val LOG = logger<GithubAccountsMigrationHelper>()

  internal fun getOldServer(): GithubServerPath? {
    try {
      if (hasOldAccount()) {
        return GithubServerPath.from(settings.host ?: GithubServerPath.DEFAULT_HOST)
      }
    }
    catch (ignore: Exception) {
      // it could be called from AnAction.update()
    }
    return null
  }

  private fun hasOldAccount(): Boolean {
    // either password-based with specified login or token based
    return ((settings.authType == GithubAuthData.AuthType.BASIC && settings.login != null) ||
            (settings.authType == GithubAuthData.AuthType.TOKEN))
  }

  /**
   * @return false if process was cancelled by user, true otherwise
   */
  @CalledInAwt
  @JvmOverloads
  fun migrate(project: Project, parentComponent: Component? = null): Boolean {
    LOG.debug("Migrating old auth")
    val login = settings.login
    val host = settings.host
    val password = passwordSafe.getPassword(CredentialAttributes(GithubSettings::class.java, GITHUB_SETTINGS_PASSWORD_KEY))
    val authType = settings.authType
    LOG.debug("Old auth data: { login: $login, host: $host, authType: $authType, password null: ${password == null} }")

    val hasAnyInfo = login != null || host != null || authType != null || password != null
    if (!hasAnyInfo) return true

    var dialogCancelled = false

    if (accountManager.accounts.isEmpty()) {
      val hostToUse = host ?: GithubServerPath.DEFAULT_HOST
      when (authType) {
        GithubAuthData.AuthType.TOKEN -> {
          LOG.debug("Migrating token auth")
          if (password != null) {
            try {
              val server = GithubServerPath.from(hostToUse)
              val progressManager = ProgressManager.getInstance()
              val accountName = progressManager.runProcessWithProgressSynchronously(ThrowableComputable<String, IOException> {
                executorFactory.create(password).execute(progressManager.progressIndicator,
                                                         GithubApiRequests.CurrentUser.get(server)).login
              }, "Accessing Github", true, project)
              val account = GithubAccountManager.createAccount(accountName, server)
              registerAccount(account, password)
            }
            catch (e: Exception) {
              LOG.debug("Failed to migrate old token-based auth. Showing dialog.", e)
              val dialog = GithubLoginDialog(executorFactory, project, parentComponent)
                .withServer(hostToUse, false).withToken(password).withError(e)
              dialogCancelled = !registerFromDialog(dialog)
            }
          }
        }
        GithubAuthData.AuthType.BASIC -> {
          LOG.debug("Migrating basic auth")
          val dialog = GithubLoginDialog(executorFactory, project, parentComponent,
                                         message = "Password authentication is no longer supported for Github.\n" +
                                                   "Personal access token can be acquired instead.")
            .withServer(hostToUse, false).withCredentials(login, password)
          dialogCancelled = !registerFromDialog(dialog)
        }
        else -> {
        }
      }
    }
    if (!dialogCancelled) clearOldAuth()
    return !dialogCancelled
  }

  private fun registerFromDialog(dialog: GithubLoginDialog): Boolean {
    DialogManager.show(dialog)
    return if (dialog.isOK) {
      registerAccount(GithubAccountManager.createAccount(dialog.getLogin(), dialog.getServer()), dialog.getToken())
      true
    }
    else false
  }

  private fun registerAccount(account: GithubAccount, token: String) {
    accountManager.accounts += account
    accountManager.updateAccountToken(account, token)
    LOG.debug("Registered account $account")
  }

  private fun clearOldAuth() {
    settings.clearAuth()
    passwordSafe.setPassword(GithubSettings::class.java, GITHUB_SETTINGS_PASSWORD_KEY, null)
  }

  companion object {
    @JvmStatic
    fun getInstance(): GithubAccountsMigrationHelper = service()
  }
}
