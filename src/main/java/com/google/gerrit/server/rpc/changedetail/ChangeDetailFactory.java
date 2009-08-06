// Copyright (C) 2008 The Android Open Source Project
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

package com.google.gerrit.server.rpc.changedetail;

import com.google.gerrit.client.data.ApprovalDetail;
import com.google.gerrit.client.data.ApprovalType;
import com.google.gerrit.client.data.ChangeDetail;
import com.google.gerrit.client.data.ChangeInfo;
import com.google.gerrit.client.data.GerritConfig;
import com.google.gerrit.client.reviewdb.Account;
import com.google.gerrit.client.reviewdb.ApprovalCategory;
import com.google.gerrit.client.reviewdb.Change;
import com.google.gerrit.client.reviewdb.ChangeApproval;
import com.google.gerrit.client.reviewdb.ChangeMessage;
import com.google.gerrit.client.reviewdb.PatchSet;
import com.google.gerrit.client.reviewdb.PatchSetAncestor;
import com.google.gerrit.client.reviewdb.Project;
import com.google.gerrit.client.reviewdb.RevId;
import com.google.gerrit.client.reviewdb.ReviewDb;
import com.google.gerrit.client.rpc.NoSuchEntityException;
import com.google.gerrit.server.account.AccountInfoCacheFactory;
import com.google.gerrit.server.patch.PatchSetInfoNotAvailableException;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.rpc.Handler;
import com.google.gerrit.server.workflow.CategoryFunction;
import com.google.gerrit.server.workflow.FunctionState;
import com.google.gwtorm.client.OrmException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Creates a {@link ChangeDetail} from a {@link Change}. */
class ChangeDetailFactory extends Handler<ChangeDetail> {
  interface Factory {
    ChangeDetailFactory create(Change.Id id);
  }

  private final GerritConfig gerritConfig;
  private final ChangeControl.Factory changeControlFactory;
  private final FunctionState.Factory functionState;
  private final PatchSetDetailFactory.Factory patchSetDetail;
  private final AccountInfoCacheFactory aic;
  private final ReviewDb db;

  private final Change.Id changeId;

  private ChangeDetail detail;
  private ChangeControl control;

  @Inject
  ChangeDetailFactory(final GerritConfig gerritConfig,
      final FunctionState.Factory functionState,
      final PatchSetDetailFactory.Factory patchSetDetail, final ReviewDb db,
      final ChangeControl.Factory changeControlFactory,
      final AccountInfoCacheFactory.Factory accountInfoCacheFactory,
      @Assisted final Change.Id id) {
    this.gerritConfig = gerritConfig;
    this.functionState = functionState;
    this.patchSetDetail = patchSetDetail;
    this.db = db;
    this.changeControlFactory = changeControlFactory;
    this.aic = accountInfoCacheFactory.create();

    this.changeId = id;
  }

  @Override
  public ChangeDetail call() throws OrmException, NoSuchEntityException,
      PatchSetInfoNotAvailableException, NoSuchChangeException {
    control = changeControlFactory.validateFor(changeId);
    final Change change = control.getChange();
    final Project proj = control.getProject();
    final PatchSet patch = db.patchSets().get(change.currentPatchSetId());
    if (patch == null) {
      throw new NoSuchEntityException();
    }

    aic.want(change.getOwner());

    detail = new ChangeDetail();
    detail.setChange(change);
    detail.setAllowsAnonymous(control.forAnonymousUser().isVisible());
    detail.setCanAbandon(change.getStatus().isOpen() && control.canAbandon());
    detail.setStarred(control.getCurrentUser().getStarredChanges().contains(
        changeId));
    loadPatchSets();
    loadMessages();
    if (change.currentPatchSetId() != null) {
      loadCurrentPatchSet();
    }
    load();
    detail.setAccounts(aic.create());
    return detail;
  }

  private void loadPatchSets() throws OrmException {
    detail.setPatchSets(db.patchSets().byChange(changeId).toList());
  }

  private void loadMessages() throws OrmException {
    detail.setMessages(db.changeMessages().byChange(changeId).toList());
    for (final ChangeMessage m : detail.getMessages()) {
      aic.want(m.getAuthor());
    }
  }

  private void load() throws OrmException {
    final List<ChangeApproval> allApprovals =
        db.changeApprovals().byChange(changeId).toList();

    if (detail.getChange().getStatus().isOpen()) {
      final FunctionState fs =
          functionState.create(detail.getChange(), allApprovals);

      final Set<ApprovalCategory.Id> missingApprovals =
          new HashSet<ApprovalCategory.Id>();

      final Set<ApprovalCategory.Id> currentActions =
          new HashSet<ApprovalCategory.Id>();

      for (final ApprovalType at : gerritConfig.getApprovalTypes()) {
        CategoryFunction.forCategory(at.getCategory()).run(at, fs);
        if (!fs.isValid(at)) {
          missingApprovals.add(at.getCategory().getId());
        }
      }
      for (final ApprovalType at : gerritConfig.getActionTypes()) {
        if (CategoryFunction.forCategory(at.getCategory()).isValid(
            control.getCurrentUser(), at, fs)) {
          currentActions.add(at.getCategory().getId());
        }
      }
      detail.setMissingApprovals(missingApprovals);
      detail.setCurrentActions(currentActions);
    }

    final HashMap<Account.Id, ApprovalDetail> ad =
        new HashMap<Account.Id, ApprovalDetail>();
    for (ChangeApproval ca : allApprovals) {
      ApprovalDetail d = ad.get(ca.getAccountId());
      if (d == null) {
        d = new ApprovalDetail(ca.getAccountId());
        ad.put(d.getAccount(), d);
      }
      d.add(ca);
    }

    final Account.Id owner = detail.getChange().getOwner();
    if (ad.containsKey(owner)) {
      // Ensure the owner always sorts to the top of the table
      //
      ad.get(owner).sortFirst();
    }

    aic.want(ad.keySet());
    detail.setApprovals(ad.values());
  }

  private void loadCurrentPatchSet() throws OrmException,
      NoSuchEntityException, PatchSetInfoNotAvailableException,
      NoSuchChangeException {
    final PatchSet.Id psId = detail.getChange().currentPatchSetId();
    final PatchSetDetailFactory loader = patchSetDetail.create(psId);
    loader.patchSet = detail.getCurrentPatchSet();
    loader.control = control;
    detail.setCurrentPatchSetDetail(loader.call());

    final HashSet<Change.Id> changesToGet = new HashSet<Change.Id>();
    final List<Change.Id> ancestorOrder = new ArrayList<Change.Id>();
    for (PatchSetAncestor a : db.patchSetAncestors().ancestorsOf(psId)) {
      for (PatchSet p : db.patchSets().byRevision(a.getAncestorRevision())) {
        final Change.Id ck = p.getId().getParentKey();
        if (changesToGet.add(ck)) {
          ancestorOrder.add(ck);
        }
      }
    }

    final RevId cprev = loader.patchSet.getRevision();
    final List<PatchSetAncestor> descendants =
        cprev != null ? db.patchSetAncestors().descendantsOf(cprev).toList()
            : Collections.<PatchSetAncestor> emptyList();
    for (final PatchSetAncestor a : descendants) {
      changesToGet.add(a.getPatchSet().getParentKey());
    }
    final Map<Change.Id, Change> m =
        db.changes().toMap(db.changes().get(changesToGet));

    final ArrayList<ChangeInfo> dependsOn = new ArrayList<ChangeInfo>();
    for (final Change.Id a : ancestorOrder) {
      final Change ac = m.get(a);
      if (ac != null) {
        aic.want(ac.getOwner());
        dependsOn.add(new ChangeInfo(ac));
      }
    }

    final ArrayList<ChangeInfo> neededBy = new ArrayList<ChangeInfo>();
    for (final PatchSetAncestor a : descendants) {
      final Change ac = m.get(a.getPatchSet().getParentKey());
      if (ac != null) {
        aic.want(ac.getOwner());
        neededBy.add(new ChangeInfo(ac));
      }
    }

    Collections.sort(neededBy, new Comparator<ChangeInfo>() {
      public int compare(final ChangeInfo o1, final ChangeInfo o2) {
        return o1.getId().get() - o2.getId().get();
      }
    });

    detail.setDependsOn(dependsOn);
    detail.setNeededBy(neededBy);
  }
}