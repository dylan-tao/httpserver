Third-Party Notices
===================

This project is a zero-dependency library: the published jar contains no
third-party code and requires no third-party libraries at runtime.

The SOURCE of this project derives in part from the following, as also
declared in the <licenses> section of pom.xml:

1. OpenJDK / JDK httpserver (com.sun.net.httpserver)
   License: GNU General Public License, version 2, with the Classpath
   Exception (https://www.gnu.org/software/classpath/license.html)
   Portions of the API-compatible surface, test harness (jdk.test.lib)
   and behavioral semantics are derived from or modeled on the OpenJDK
   jdk.httpserver module.

2. NanoHTTPD (https://github.com/NanoHttpd/nanohttpd)
   License: MIT License (https://opensource.org/licenses/MIT),
   see https://github.com/NanoHttpd/nanohttpd/blob/efb2ebf85a2b06f7c508aba9eaad5377e3a01e81/LICENSE.md
   Portions of the original HTTP/1.1 parsing design derive from NanoHTTPD.

No other third-party code is incorporated in this project. The test suite
uses TestNG (Apache License 2.0) at test scope only; it is not distributed
with the artifact.
