/*
 * uIAM - the IAM for microservices
 * Copyright (C) SoftInstigate Srl
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.uiam.plugins.authentication;

import io.uiam.plugins.ConfigurablePlugin;
import io.undertow.security.api.AuthenticationMechanism;
import io.undertow.security.api.SecurityContext;
import io.undertow.server.HttpServerExchange;

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
public interface PluggableAuthenticationMechanism extends AuthenticationMechanism, ConfigurablePlugin {
        @Override
        public AuthenticationMechanismOutcome authenticate(final HttpServerExchange exchange,
                        final SecurityContext securityContext);

        @Override
        public ChallengeResult sendChallenge(final HttpServerExchange exchange, final SecurityContext securityContext);

        public String getMechanismName();
}
