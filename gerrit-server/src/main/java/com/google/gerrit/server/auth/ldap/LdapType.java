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

package com.google.gerrit.server.auth.ldap;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;

abstract class LdapType {
  static final LdapType RFC_2307 = new Rfc2307();

  static LdapType guessType(final DirContext ctx) throws NamingException {
    final Attributes rootAtts = ctx.getAttributes("");
    Attribute supported = rootAtts.get("supportedCapabilities");
    if (supported != null && supported.contains("1.2.840.113556.1.4.800")) {
      return new ActiveDirectory(rootAtts);
    }

    return RFC_2307;
  }

  abstract String groupPattern();

  abstract String groupMemberPattern();

  abstract String accountFullName();

  abstract String accountEmailAddress();

  abstract String accountSshUserName();

  abstract String accountMemberField();

  abstract String accountPattern();

  private static class Rfc2307 extends LdapType {
    @Override
    String groupPattern() {
      return "(cn=${groupname})";
    }

    @Override
    String groupMemberPattern() {
      return "(memberUid=${username})";
    }

    @Override
    String accountFullName() {
      return "displayName";
    }

    @Override
    String accountEmailAddress() {
      return "mail";
    }

    @Override
    String accountSshUserName() {
      return "uid";
    }

    @Override
    String accountMemberField() {
      return null; // Not defined in RFC 2307
    }

    @Override
    String accountPattern() {
      return "(uid=${username})";
    }
  }

  private static class ActiveDirectory extends LdapType {
    private final String defaultDomain;

    ActiveDirectory(final Attributes atts) throws NamingException {
      // Convert "defaultNamingContext: DC=foo,DC=example,DC=com" into
      // the a standard DNS name as we would expect to find in the suffix
      // part of the userPrincipalName.
      //
      Attribute defaultNamingContext = atts.get("defaultNamingContext");
      if (defaultNamingContext == null || defaultNamingContext.size() < 1) {
        throw new NamingException("rootDSE has no defaultNamingContext");
      }

      final StringBuilder b = new StringBuilder();
      for (String n : ((String) defaultNamingContext.get(0)).split(", *")) {
        if (n.toUpperCase().startsWith("DC=")) {
          if (b.length() > 0) {
            b.append('.');
          }
          b.append(n.substring(3));
        }
      }
      defaultDomain = b.toString();
    }

    @Override
    String groupPattern() {
      return "(&(objectClass=group)(cn=${groupname}))";
    }

    @Override
    String groupMemberPattern() {
      return null; // Active Directory uses memberOf in the account
    }

    @Override
    String accountFullName() {
      return "${givenName} ${sn}";
    }

    @Override
    String accountEmailAddress() {
      return "mail";
    }

    @Override
    String accountSshUserName() {
      return "${sAMAccountName.toLowerCase}";
    }

    @Override
    String accountMemberField() {
      return "memberOf";
    }

    @Override
    String accountPattern() {
      return "(&(objectClass=user)(sAMAccountName=${username}))";
    }
  }
}
