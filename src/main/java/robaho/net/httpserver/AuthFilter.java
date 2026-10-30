/*
 * Copyright (c) 2006, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package robaho.net.httpserver;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.IOException;
import java.security.Principal;

/**
 * A {@link Filter} that authenticates every request through a JDK {@link Authenticator} before the handler runs: on
 * success the authenticated {@link HttpPrincipal} is exposed via {@code HttpExchange.getPrincipal()}; on failure the
 * filter answers with the authenticator's 401/403 result and the handler is never invoked. Register it as a context
 * filter: {@code context.getFilters().add(new AuthFilter(authenticator))}.
 */
public class AuthFilter extends Filter {

  // volatile: setAuthenticator runs on admin threads while request threads
  // read the field - without the happens-before edge an in-flight
  // connection could serve unauthenticated on a stale null forever, and a
  // concurrent null-set could NPE between the check and the call
  private volatile Authenticator authenticator;

  /**
   * create the filter around the given authenticator; it may be replaced later via {@link #setAuthenticator}
   */
  public AuthFilter(Authenticator authenticator) {
    this.authenticator = authenticator;
  }

  /** the filter description shown in diagnostics */
  public String description() {
    return "Authentication filter";
  }

  /** replace the authenticator (takes effect for subsequent requests) */
  public void setAuthenticator(Authenticator a) {
    authenticator = a;
  }

  /**
   * The filter's implementation, which is invoked by the server
   */
  public void doFilter(HttpExchange t, Filter.Chain chain) throws IOException {
    if (authenticator != null) {
      Authenticator.Result r = authenticator.authenticate(t);
      if (r instanceof Authenticator.Success s) {
        PrincipalExchange e = (PrincipalExchange) t;
        e.setPrincipal(s.getPrincipal());
        chain.doFilter(t);
      } else if (r instanceof Authenticator.Retry ry) {
        // the reply goes out IMMEDIATELY: consuming the request body
        // first (the JDK-inherited behavior) read the RAW socket
        // stream forever on upgrade/CONNECT requests (getRequestBody
        // hands those out unframed - an unauthenticated client pinned
        // the connection thread and the 401 never went out), and let
        // a drip-fed body pin the thread even on normal requests.
        // the framework's own bounded drain (64KB/1s) after the
        // response handles the leftover bytes, exactly as it does
        // for any handler that ignores the body
        t.sendResponseHeaders(ry.getResponseCode(), -1);
      } else if (r instanceof Authenticator.Failure f) {
        t.sendResponseHeaders(f.getResponseCode(), -1);
      }
    } else {
      chain.doFilter(t);
    }
  }

  interface PrincipalExchange {

    void setPrincipal(HttpPrincipal p);

    Principal getPrincipal();

  }

}
