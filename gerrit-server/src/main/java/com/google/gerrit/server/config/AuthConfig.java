// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.config;

import com.google.gerrit.common.auth.openid.OpenIdProviderPattern;
import com.google.gerrit.reviewdb.AccountExternalId;
import com.google.gerrit.reviewdb.AccountGroup;
import com.google.gerrit.reviewdb.AuthType;
import com.google.gerrit.reviewdb.SystemConfig;
import com.google.gwtjsonrpc.server.SignedToken;
import com.google.gwtjsonrpc.server.XsrfException;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.jgit.lib.Config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Authentication related settings from {@code gerrit.config}. */
@Singleton
public class AuthConfig {
  private final AuthType authType;
  private final String httpHeader;
  private final String logoutUrl;
  private final List<OpenIdProviderPattern> trustedOpenIDs;
  private final List<OpenIdProviderPattern> allowedOpenIDs;
  private final SignedToken emailReg;

  private final AccountGroup.Id administratorGroup;
  private final Set<AccountGroup.Id> anonymousGroups;
  private final Set<AccountGroup.Id> registeredGroups;
  private final AccountGroup.Id batchUsersGroup;

  private final boolean allowGoogleAccountUpgrade;

  @Inject
  AuthConfig(@GerritServerConfig final Config cfg, final SystemConfig s)
      throws XsrfException {
    authType = toType(cfg);
    httpHeader = cfg.getString("auth", null, "httpheader");
    logoutUrl = cfg.getString("auth", null, "logouturl");
    trustedOpenIDs = toPatterns(cfg, "trustedOpenID");
    allowedOpenIDs = toPatterns(cfg, "allowedOpenID");
    emailReg = new SignedToken(5 * 24 * 60 * 60, s.registerEmailPrivateKey);

    final HashSet<AccountGroup.Id> r = new HashSet<AccountGroup.Id>(2);
    r.add(s.anonymousGroupId);
    r.add(s.registeredGroupId);
    registeredGroups = Collections.unmodifiableSet(r);
    anonymousGroups = Collections.singleton(s.anonymousGroupId);
    administratorGroup = s.adminGroupId;
    batchUsersGroup = s.batchUsersGroupId;

    if (authType == AuthType.OPENID) {
      allowGoogleAccountUpgrade =
          cfg.getBoolean("auth", "allowgoogleaccountupgrade", false);
    } else {
      allowGoogleAccountUpgrade = false;
    }
  }

  private static List<OpenIdProviderPattern> toPatterns(Config cfg, String name) {
    String[] s = cfg.getStringList("auth", null, name);
    if (s.length == 0) {
      s = new String[] {"http://", "https://"};
    }

    List<OpenIdProviderPattern> r = new ArrayList<OpenIdProviderPattern>();
    for (String pattern : s) {
      r.add(OpenIdProviderPattern.create(pattern));
    }
    return Collections.unmodifiableList(r);
  }

  private static AuthType toType(final Config cfg) {
    return ConfigUtil.getEnum(cfg, "auth", null, "type", AuthType.OPENID);
  }

  /** Type of user authentication used by this Gerrit server. */
  public AuthType getAuthType() {
    return authType;
  }

  public String getLoginHttpHeader() {
    return httpHeader;
  }

  public String getLogoutURL() {
    return logoutUrl;
  }

  public SignedToken getEmailRegistrationToken() {
    return emailReg;
  }

  public boolean isAllowGoogleAccountUpgrade() {
    return allowGoogleAccountUpgrade;
  }

  /** Identity of the magic group with full powers. */
  public AccountGroup.Id getAdministratorsGroup() {
    return administratorGroup;
  }

  /** Identity of the group whose service is degraded to lower priority. */
  public AccountGroup.Id getBatchUsersGroup() {
    return batchUsersGroup;
  }

  /** Groups that all users, including anonymous users, belong to. */
  public Set<AccountGroup.Id> getAnonymousGroups() {
    return anonymousGroups;
  }

  /** Groups that all users who have created an account belong to. */
  public Set<AccountGroup.Id> getRegisteredGroups() {
    return registeredGroups;
  }

  /** OpenID identities which the server permits for authentication. */
  public List<OpenIdProviderPattern> getAllowedOpenIDs() {
    return allowedOpenIDs;
  }

  public boolean isIdentityTrustable(final Collection<AccountExternalId> ids) {
    switch (getAuthType()) {
      case DEVELOPMENT_BECOME_ANY_ACCOUNT:
      case HTTP:
      case HTTP_LDAP:
      case LDAP:
      case LDAP_BIND:
        // Its safe to assume yes for an HTTP authentication type, as the
        // only way in is through some external system that the admin trusts
        //
        return true;

      case OPENID:
        // All identities must be trusted in order to trust the account.
        //
        for (final AccountExternalId e : ids) {
          if (!isTrusted(e)) {
            return false;
          }
        }
        return true;

      default:
        // Assume not, we don't understand the login format.
        //
        return false;
    }
  }

  private boolean isTrusted(final AccountExternalId id) {
    if (id.isScheme(AccountExternalId.LEGACY_GAE)) {
      // Assume this is a trusted token, its a legacy import from
      // a fairly well respected provider and only takes effect if
      // the administrator has the import still enabled
      //
      return isAllowGoogleAccountUpgrade();
    }

    if (id.isScheme(AccountExternalId.SCHEME_MAILTO)) {
      // mailto identities are created by sending a unique validation
      // token to the address and asking them to come back to the site
      // with that token.
      //
      return true;
    }

    if (id.isScheme(AccountExternalId.SCHEME_UUID)) {
      // UUID identities are absolutely meaningless and cannot be
      // constructed through any normal login process we use.
      //
      return true;
    }

    if (id.isScheme(AccountExternalId.SCHEME_USERNAME)) {
      // We can trust their username, its local to our server only.
      //
      return true;
    }

    for (final OpenIdProviderPattern p : trustedOpenIDs) {
      if (p.matches(id)) {
        return true;
      }
    }
    return false;
  }
}
