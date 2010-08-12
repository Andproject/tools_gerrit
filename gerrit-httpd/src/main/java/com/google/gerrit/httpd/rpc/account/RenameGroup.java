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

package com.google.gerrit.httpd.rpc.account;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.gerrit.common.errors.NameAlreadyUsedException;
import com.google.gerrit.httpd.rpc.Handler;
import com.google.gerrit.reviewdb.AccountGroup;
import com.google.gerrit.reviewdb.AccountGroupName;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.account.GroupControl;
import com.google.gerrit.server.account.NoSuchGroupException;
import com.google.gerrit.server.util.FutureUtil;
import com.google.gwtjsonrpc.client.VoidResult;
import com.google.gwtorm.client.OrmDuplicateKeyException;
import com.google.gwtorm.client.OrmException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.internal.Lists;

import java.util.Collections;
import java.util.List;

class RenameGroup extends Handler<VoidResult> {
  interface Factory {
    RenameGroup create(AccountGroup.Id id, String newName);
  }

  private final ReviewDb db;
  private final GroupCache groupCache;
  private final GroupControl.Factory groupControlFactory;

  private final AccountGroup.Id groupId;
  private final String newName;

  @Inject
  RenameGroup(final ReviewDb db, final GroupCache groupCache,
      final GroupControl.Factory groupControlFactory,

      @Assisted final AccountGroup.Id groupId, @Assisted final String newName) {
    this.db = db;
    this.groupCache = groupCache;
    this.groupControlFactory = groupControlFactory;

    this.groupId = groupId;
    this.newName = newName;
  }

  @Override
  public VoidResult call() throws OrmException, NameAlreadyUsedException,
      NoSuchGroupException {
    final GroupControl ctl = groupControlFactory.validateFor(groupId);
    final AccountGroup group = db.accountGroups().get(groupId);
    if (group == null || !ctl.isOwner()) {
      throw new NoSuchGroupException(groupId);
    }

    final AccountGroup.NameKey old = group.getNameKey();
    final AccountGroup.NameKey key = new AccountGroup.NameKey(newName);

    try {
      final AccountGroupName id = new AccountGroupName(key, groupId);
      db.accountGroupNames().insert(Collections.singleton(id));
    } catch (OrmDuplicateKeyException dupeErr) {
      // If we are using this identity, don't report the exception.
      //
      AccountGroupName other = db.accountGroupNames().get(key);
      if (other != null && other.getId().equals(groupId)) {
        return VoidResult.INSTANCE;
      }

      // Otherwise, someone else has this identity.
      //
      throw new NameAlreadyUsedException();
    }

    group.setNameKey(key);
    db.accountGroups().update(Collections.singleton(group));

    AccountGroupName priorName = db.accountGroupNames().get(old);
    if (priorName != null) {
      db.accountGroupNames().delete(Collections.singleton(priorName));
    }

    List<ListenableFuture<Void>> evictions = Lists.newArrayList();
    evictions.add(groupCache.evictAsync(group));
    evictions.add(groupCache.evictAfterRenameAsync(old));
    FutureUtil.waitFor(evictions);
    return VoidResult.INSTANCE;
  }
}
