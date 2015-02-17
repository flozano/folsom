/*
 * Copyright (c) 2015 Spotify AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.spotify.folsom;

import com.google.common.base.Charsets;
import com.google.common.net.HostAndPort;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class MisbehavingServerTest {
  private Server server;

  @Before
  public void setup() throws Exception {
  }

  @After
  public void tearDown() throws Exception {
    server.stop();
  }

  @Test
  public void testInvalidAsciiResponse() throws Throwable {
    testAsciiGet("HIPPO\r\n", "Unexpected line: HIPPO");
  }

  @Test
  public void testInvalidAsciiResponse2() throws Throwable {
    testAsciiGet("HIPPOS\r\n", "Unexpected line: HIPPOS");
  }

  @Test
  public void testInvalidAsciiResponse3() throws Throwable {
    testAsciiGet("AAAAAAAAAAAAAAARGH\r\n", "Unexpected line: AAAAAAAAAAAAAAARGH");
  }

  @Test
  public void testAsciiNotANumber() throws Throwable {
    testAsciiGet("123ABC\r\n", "Unexpected line: 123ABC");
  }

  @Test
  public void testEmptyAsciiResponse() throws Throwable {
    testAsciiGet("\r\n", "Unexpected line: ");
  }

  @Test
  public void testNotNewline() throws Throwable {
    testAsciiGet("\rFoo\n", "Expected newline, got something else");
  }

  @Test
  public void testBadAsciiGet() throws Throwable {
    testAsciiGet("VALUE\r\n", "String index out of range: -1");
  }

  @Test
  public void testBadAsciiGet2() throws Throwable {
    testAsciiGet("VALUE \r\n", "Unexpected line: VALUE ");
  }

  @Test
  public void testBadAsciiGet3() throws Throwable {
    testAsciiGet("VALUE key\r\n", "Unexpected line: VALUE key");
  }

  @Test
  public void testBadAsciiGet4() throws Throwable {
    testAsciiGet("VALUE key 123\r\n", "Unexpected line: VALUE key 123");
  }

  @Test
  public void testBadAsciiGet5() throws Throwable {
    testAsciiGet("VALUE key 123 456\r\n", "Timeout");
  }

  @Test
  public void testBadAsciiGet6() throws Throwable {
    testAsciiGet("VALUE key 123 0\r\nfoo\r\n", "Unexpected end of data block: foo");
  }

  @Test
  public void testBadAsciiGet7() throws Throwable {
    testAsciiGet("VALUE key 123 0\r\n\r\nSTORED\r\n", "Unexpected line: STORED");
  }

  private void testAsciiGet(String response, String expectedError) throws InterruptedException, IOException {
    MemcacheClient<String> client = setupAscii(response);
    try {
      client.get("key").get();
      fail();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      assertEquals(MemcacheClosedException.class, cause.getClass());
      assertEquals(expectedError, cause.getMessage());
    }
  }

  private MemcacheClient<String> setupAscii(String response) throws IOException {
    server = new Server(response);
    MemcacheClient<String> client = MemcacheClientBuilder.newStringClient()
            .withAddress(HostAndPort.fromParts("localhost", server.port))
            .withRequestTimeoutMillis(100L)
            .withRetry(false)
            .connectAscii();
    IntegrationTest.awaitConnected(client);
    return client;
  }

  private static class Server {
    private final int port;
    private final ServerSocket serverSocket;
    private final Thread thread;

    private volatile Throwable failure;
    private volatile Socket socket;

    private Server(String responseString) throws IOException {
      final byte[] response = responseString.getBytes(Charsets.UTF_8);
      serverSocket = new ServerSocket(0);
      port = serverSocket.getLocalPort();
      thread = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            socket = serverSocket.accept();
            handleConnection(socket);
          } catch (Throwable e) {
            failure = e;
            failure.printStackTrace();
          }
        }

        private void handleConnection(Socket socket) throws Exception {
          BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()), 1);
          String s = reader.readLine();
          if (s.startsWith("get ")) {
            // Don't need to read any more lines
          } else {
            throw new RuntimeException("Unhandled command: " + s);
          }
          socket.getOutputStream().write(response);
          socket.getOutputStream().flush();
        }
      });
      thread.start();
    }

    public void stop() throws Exception {
      thread.join();
      serverSocket.close();
      if (socket != null) {
        socket.close();
      }
      if (failure != null) {
        fail(failure.getClass().getSimpleName() + ": " + failure.getMessage());
      }
    }
  }
}
