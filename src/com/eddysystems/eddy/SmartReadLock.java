/* SmartReadLock: Grab a read lock, handling interrupts and dumb mode correctly */

package com.eddysystems.eddy;

import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import utility.Interrupts;

public class SmartReadLock {
  final private DumbService dumb;

  // for unfettered read access
  private final @NotNull Object accessTokenLock = new Object();
  private AccessToken accessToken = null;

  public SmartReadLock(final Project project) {
    this.dumb = DumbService.getInstance(project);
  }

  public void lock() {
    assert accessToken == null;

    // wait for smart mode and get a new access token
    while (true) {
      dumb.waitForSmartMode();

      // Check if we were interrupted
      if (Thread.interrupted()) throw new ThreadDeath();
      if (Interrupts.pending != 0) Interrupts.checkInterrupts();

      // get a new read access token
      synchronized (accessTokenLock) {
        accessToken = ApplicationManager.getApplication().acquireReadActionLock();
      }
      // did we become dumb?
      if (!dumb.isDumb())
        break;
      else // release lock and try again
        unlock();
    }
  }

  // Safe to call in both locked and unlocked states
  public boolean unlock() {
    synchronized (accessTokenLock) {
      if (accessToken != null) {
        accessToken.finish();
        accessToken = null;
        return true;
      }
      return false;
    }
  }
}
