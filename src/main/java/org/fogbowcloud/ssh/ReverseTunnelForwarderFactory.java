/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.fogbowcloud.ssh;

import java.util.Map;

import org.apache.sshd.common.TcpipForwarder;
import org.apache.sshd.common.TcpipForwarderFactory;
import org.apache.sshd.common.forward.DefaultTcpipForwarder;
import org.apache.sshd.common.session.ConnectionService;

/**
 * The default {link TcpipForwarderFactory} implementation.
 * 
 * @see DefaultTcpipForwarder
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class ReverseTunnelForwarderFactory implements TcpipForwarderFactory {
	private Map<String, Integer> tokens;

	public ReverseTunnelForwarderFactory(Map<String, Integer> tokens) {
		this.tokens = tokens;
	}

	public TcpipForwarder create(ConnectionService service) {
		return new ReverseTunnelForwarder(tokens, service);
	}
}