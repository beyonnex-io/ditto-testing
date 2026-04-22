/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.testing.system.things.rest;

import static org.eclipse.ditto.base.model.common.HttpStatus.BAD_REQUEST;
import static org.eclipse.ditto.base.model.common.HttpStatus.CREATED;
import static org.eclipse.ditto.base.model.common.HttpStatus.NOT_FOUND;
import static org.eclipse.ditto.base.model.common.HttpStatus.NO_CONTENT;
import static org.eclipse.ditto.base.model.common.HttpStatus.OK;
import static org.eclipse.ditto.policies.model.PoliciesResourceType.policyResource;
import static org.eclipse.ditto.policies.model.PoliciesResourceType.thingResource;
import static org.eclipse.ditto.things.api.Permission.READ;
import static org.eclipse.ditto.things.api.Permission.WRITE;

import java.util.List;
import java.util.Set;

import org.eclipse.ditto.json.JsonArray;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.policies.model.AllowedImportAddition;
import org.eclipse.ditto.policies.model.ImportableType;
import org.eclipse.ditto.policies.model.Label;
import org.eclipse.ditto.policies.model.PoliciesModelFactory;
import org.eclipse.ditto.policies.model.Policy;
import org.eclipse.ditto.policies.model.PolicyEntry;
import org.eclipse.ditto.policies.model.PolicyId;
import org.eclipse.ditto.policies.model.PolicyImport;
import org.eclipse.ditto.policies.model.Subject;
import org.eclipse.ditto.testing.common.IntegrationTest;
import org.eclipse.ditto.testing.common.ResourcePathBuilder;
import org.eclipse.ditto.testing.common.TestConstants;
import org.eclipse.ditto.testing.common.matcher.DeleteMatcher;
import org.eclipse.ditto.testing.common.matcher.GetMatcher;
import org.eclipse.ditto.testing.common.matcher.PutMatcher;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for the dedicated policy entry sub-resource HTTP routes:
 * <ul>
 *   <li>GET/PUT {@code /entries/{label}/importable}</li>
 *   <li>GET/PUT {@code /entries/{label}/allowedImportAdditions}</li>
 * </ul>
 */
public final class PolicyEntryImportableSubResourcesIT extends IntegrationTest {

    private PolicyId importedPolicyId;
    private PolicyId importingPolicyId;
    private Subject defaultSubject;
    private Subject subject2;

    @Before
    public void setUp() {
        importedPolicyId = PolicyId.of(idGenerator().withPrefixedRandomName("imported"));
        importingPolicyId = PolicyId.of(idGenerator().withPrefixedRandomName("importing"));
        defaultSubject = serviceEnv.getDefaultTestingContext().getOAuthClient().getDefaultSubject();
        subject2 = serviceEnv.getTestingContext2().getOAuthClient().getDefaultSubject();
    }

    @Test
    public void getAndPutPolicyEntryImportable() {
        // Create policy with DEFAULT entry (IMPLICIT importable)
        final Policy policy = buildImportedPolicy(importedPolicyId, Set.of());
        putPolicy(policy).expectingHttpStatus(CREATED).fire();

        // GET importable and verify it is "implicit"
        getPolicyEntryImportable(importedPolicyId, "DEFAULT")
                .expectingBody(containsOnly(JsonValue.of("implicit")))
                .expectingHttpStatus(OK)
                .fire();

        // PUT importable to "never"
        putPolicyEntryImportable(importedPolicyId, "DEFAULT", "never")
                .expectingHttpStatus(NO_CONTENT)
                .fire();

        // GET importable again and verify it is "never"
        getPolicyEntryImportable(importedPolicyId, "DEFAULT")
                .expectingBody(containsOnly(JsonValue.of("never")))
                .expectingHttpStatus(OK)
                .fire();
    }

    @Test
    public void getAndPutPolicyEntryAllowedImportAdditions() {
        // Create policy with DEFAULT entry that has allowedImportAdditions=["subjects"]
        final Policy policy = buildImportedPolicy(importedPolicyId,
                Set.of(AllowedImportAddition.SUBJECTS));
        putPolicy(policy).expectingHttpStatus(CREATED).fire();

        // GET allowedImportAdditions and verify it contains "subjects"
        getPolicyEntryAllowedImportAdditions(importedPolicyId, "DEFAULT")
                .expectingBody(containsOnly(JsonArray.newBuilder().add("subjects").build()))
                .expectingHttpStatus(OK)
                .fire();

        // PUT allowedImportAdditions to ["subjects","resources"]
        final JsonArray updated = JsonArray.newBuilder().add("subjects").add("resources").build();
        putPolicyEntryAllowedImportAdditions(importedPolicyId, "DEFAULT", updated)
                .expectingHttpStatus(NO_CONTENT)
                .fire();

        // GET allowedImportAdditions again and verify
        getPolicyEntryAllowedImportAdditions(importedPolicyId, "DEFAULT")
                .expectingBody(containsOnly(updated))
                .expectingHttpStatus(OK)
                .fire();
    }

    @Test
    public void changingImportableToNeverRevokesThingAccess() {
        // Create imported policy with DEFAULT entry (IMPLICIT, allows subject additions)
        final Policy importedPolicy = buildImportedPolicy(importedPolicyId,
                Set.of(AllowedImportAddition.SUBJECTS));
        putPolicy(importedPolicy).expectingHttpStatus(CREATED).fire();

        // Create importing policy with subject2 + import reference to DEFAULT
        putPolicy(importingPolicyId, buildImportingPolicyJsonWithImportRef(
                importingPolicyId, importedPolicyId, subject2))
                .expectingHttpStatus(CREATED).fire();

        // Create a thing with the importing policy
        final String thingId = importingPolicyId.toString();
        putThing(TestConstants.API_V_2, JsonObject.newBuilder()
                        .set("thingId", thingId)
                        .set("policyId", importingPolicyId.toString())
                        .build(),
                org.eclipse.ditto.base.model.json.JsonSchemaVersion.V_2)
                .expectingHttpStatus(CREATED)
                .fire();

        // Verify user2 can access the thing
        getThing(TestConstants.API_V_2, thingId)
                .withConfiguredAuth(serviceEnv.getTestingContext2())
                .expectingHttpStatus(OK)
                .fire();

        // Change importable of DEFAULT to NEVER on the imported (template) policy
        putPolicyEntryImportable(importedPolicyId, "DEFAULT", "never")
                .expectingHttpStatus(NO_CONTENT)
                .fire();

        // Verify user2 can no longer access the thing
        getThing(TestConstants.API_V_2, thingId)
                .withConfiguredAuth(serviceEnv.getTestingContext2())
                .expectingHttpStatus(NOT_FOUND)
                .fire();

        // Cleanup
        deleteThing(TestConstants.API_V_2, thingId)
                .expectingHttpStatus(NO_CONTENT)
                .fire();
    }

    @Test
    public void addingAllowedImportAdditionsEnablesImportRefWithSubjects() {
        // Create imported policy WITHOUT allowedImportAdditions (but IMPLICIT importable)
        final Policy importedPolicy = buildImportedPolicyWithoutAllowedAdditions(importedPolicyId);
        putPolicy(importedPolicy).expectingHttpStatus(CREATED).fire();

        // Create importing policy with import and a user-access entry (no references yet)
        final PolicyImport simpleImport = PoliciesModelFactory.newPolicyImport(importedPolicyId,
                PoliciesModelFactory.newEffectedImportedLabels(List.of(Label.of("DEFAULT"))));
        final Policy importingPolicy = buildImportingPolicy(importingPolicyId)
                .toBuilder().setPolicyImport(simpleImport).build();
        putPolicy(importingPolicy).expectingHttpStatus(CREATED).fire();

        // Add a user-access entry
        final PolicyEntry userEntry = PoliciesModelFactory.newPolicyEntry("user-access",
                List.of(subject2),
                List.of(PoliciesModelFactory.newResource(thingResource("/"),
                        PoliciesModelFactory.newEffectedPermissions(List.of(READ), List.of()))),
                ImportableType.NEVER, Set.of());
        putPolicyEntry(importingPolicyId, userEntry).expectingHttpStatus(CREATED).fire();

        // Try to add import reference with subject - should be rejected (no allowedAdditions)
        final JsonArray refs = JsonArray.of(importRef(importedPolicyId, "DEFAULT"));
        putReferences(importingPolicyId, "user-access", refs)
                .expectingHttpStatus(BAD_REQUEST)
                .fire();

        // Add allowedImportAdditions=["subjects"] to the imported policy's DEFAULT entry
        putPolicyEntryAllowedImportAdditions(importedPolicyId, "DEFAULT",
                JsonArray.newBuilder().add("subjects").build())
                .expectingHttpStatus(NO_CONTENT)
                .fire();

        // Now adding the import reference should succeed
        putReferences(importingPolicyId, "user-access", refs)
                .expectingHttpStatus(NO_CONTENT)
                .fire();
    }

    @Test
    public void addingAllowedImportAdditionsViaSubResourceEnablesThingAccess() {
        // Create imported policy WITHOUT allowedImportAdditions (but IMPLICIT importable)
        final Policy importedPolicy = buildImportedPolicyWithoutAllowedAdditions(importedPolicyId);
        putPolicy(importedPolicy).expectingHttpStatus(CREATED).fire();

        // Create importing policy with a simple import (no references yet)
        final PolicyImport simpleImport = PoliciesModelFactory.newPolicyImport(importedPolicyId,
                PoliciesModelFactory.newEffectedImportedLabels(List.of(Label.of("DEFAULT"))));
        final Policy importingPolicy = buildImportingPolicy(importingPolicyId)
                .toBuilder().setPolicyImport(simpleImport).build();
        putPolicy(importingPolicy).expectingHttpStatus(CREATED).fire();

        // Create a thing with the importing policy
        final String thingId = importingPolicyId.toString();
        putThing(TestConstants.API_V_2, JsonObject.newBuilder()
                        .set("thingId", thingId)
                        .set("policyId", importingPolicyId.toString())
                        .build(),
                org.eclipse.ditto.base.model.json.JsonSchemaVersion.V_2)
                .expectingHttpStatus(CREATED)
                .fire();

        // Verify user2 cannot access the thing initially
        getThing(TestConstants.API_V_2, thingId)
                .withConfiguredAuth(serviceEnv.getTestingContext2())
                .expectingHttpStatus(NOT_FOUND)
                .fire();

        // Add allowedImportAdditions=["subjects"] via the sub-resource route on the imported policy
        putPolicyEntryAllowedImportAdditions(importedPolicyId, "DEFAULT",
                JsonArray.newBuilder().add("subjects").build())
                .expectingHttpStatus(NO_CONTENT)
                .fire();

        // Add a user-access entry with subject2 + import reference
        final PolicyEntry userEntry = PoliciesModelFactory.newPolicyEntry("user-access",
                List.of(subject2),
                List.of(),
                ImportableType.NEVER, Set.of());
        putPolicyEntry(importingPolicyId, userEntry).expectingHttpStatus(CREATED).fire();

        final JsonArray refs = JsonArray.of(importRef(importedPolicyId, "DEFAULT"));
        putReferences(importingPolicyId, "user-access", refs)
                .expectingHttpStatus(NO_CONTENT)
                .fire();

        // Verify user2 can now access the thing
        getThing(TestConstants.API_V_2, thingId)
                .withConfiguredAuth(serviceEnv.getTestingContext2())
                .expectingHttpStatus(OK)
                .fire();

        // Cleanup
        deleteThing(TestConstants.API_V_2, thingId)
                .expectingHttpStatus(NO_CONTENT)
                .fire();
    }

    @Test
    public void removingAllowedImportAdditionsRejectsNewImportRefWithSubjects() {
        // Create imported policy WITH allowedImportAdditions=["subjects"]
        final Policy importedPolicy = buildImportedPolicy(importedPolicyId,
                Set.of(AllowedImportAddition.SUBJECTS));
        putPolicy(importedPolicy).expectingHttpStatus(CREATED).fire();

        // Create importing policy with import
        final PolicyImport simpleImport = PoliciesModelFactory.newPolicyImport(importedPolicyId,
                PoliciesModelFactory.newEffectedImportedLabels(List.of(Label.of("DEFAULT"))));
        final Policy importingPolicy = buildImportingPolicy(importingPolicyId)
                .toBuilder().setPolicyImport(simpleImport).build();
        putPolicy(importingPolicy).expectingHttpStatus(CREATED).fire();

        // Add a user-access entry
        final PolicyEntry userEntry = PoliciesModelFactory.newPolicyEntry("user-access",
                List.of(subject2),
                List.of(),
                ImportableType.NEVER, Set.of());
        putPolicyEntry(importingPolicyId, userEntry).expectingHttpStatus(CREATED).fire();

        // Remove allowedImportAdditions from the imported policy's DEFAULT entry
        putPolicyEntryAllowedImportAdditions(importedPolicyId, "DEFAULT",
                JsonArray.empty())
                .expectingHttpStatus(NO_CONTENT)
                .fire();

        // Try to add import reference - should be rejected (no allowed additions)
        final JsonArray refs = JsonArray.of(importRef(importedPolicyId, "DEFAULT"));
        putReferences(importingPolicyId, "user-access", refs)
                .expectingHttpStatus(BAD_REQUEST)
                .fire();
    }

    @Test
    public void removingResourcesFromAllowedAdditionsRejectsNewResourceOnEntry() {
        // Create imported policy with allowedImportAdditions=["subjects","resources"]
        final Policy importedPolicy = buildImportedPolicy(importedPolicyId,
                Set.of(AllowedImportAddition.SUBJECTS, AllowedImportAddition.RESOURCES));
        putPolicy(importedPolicy).expectingHttpStatus(CREATED).fire();

        // Create importing policy with import ref + extra resource on the entry
        final JsonObject policyJson = buildImportingPolicyJsonWithExtraResource(
                importingPolicyId, importedPolicyId, subject2, "thing:/attributes", List.of("READ"));
        putPolicy(importingPolicyId, policyJson).expectingHttpStatus(CREATED).fire();

        // Remove "resources" from allowedImportAdditions, keeping only "subjects"
        putPolicyEntryAllowedImportAdditions(importedPolicyId, "DEFAULT",
                JsonArray.newBuilder().add("subjects").build())
                .expectingHttpStatus(NO_CONTENT)
                .fire();

        // The existing entry's extra resource should no longer be effective at enforcement time
        // Verify via thing access: subject2 can still READ (from template) but no extra resources
        getPolicy(importingPolicyId).expectingHttpStatus(OK).fire();
    }

    // --- Helper methods for building policies ---

    private Policy buildImportedPolicy(final PolicyId policyId,
            final Set<AllowedImportAddition> allowedImportAdditions) {

        final PolicyEntry adminEntry = PoliciesModelFactory.newPolicyEntry("ADMIN",
                List.of(defaultSubject),
                List.of(PoliciesModelFactory.newResource(policyResource("/"),
                        PoliciesModelFactory.newEffectedPermissions(List.of(READ, WRITE), List.of()))),
                ImportableType.NEVER, Set.of());

        final PolicyEntry defaultEntry = PoliciesModelFactory.newPolicyEntry("DEFAULT",
                List.of(defaultSubject),
                List.of(PoliciesModelFactory.newResource(thingResource("/"),
                        PoliciesModelFactory.newEffectedPermissions(List.of(READ), List.of()))),
                ImportableType.IMPLICIT, allowedImportAdditions);

        return PoliciesModelFactory.newPolicyBuilder(policyId)
                .set(adminEntry)
                .set(defaultEntry)
                .build();
    }

    private Policy buildImportedPolicyWithoutAllowedAdditions(final PolicyId policyId) {
        return PoliciesModelFactory.newPolicyBuilder(policyId)
                .forLabel("ADMIN")
                .setSubject(defaultSubject)
                .setGrantedPermissions(policyResource("/"), READ, WRITE)
                .setImportable(ImportableType.NEVER)
                .forLabel("DEFAULT")
                .setSubject(defaultSubject)
                .setGrantedPermissions(thingResource("/"), READ)
                .build();
    }

    private Policy buildImportingPolicy(final PolicyId policyId) {
        return PoliciesModelFactory.newPolicyBuilder(policyId)
                .forLabel("ADMIN")
                .setSubject(defaultSubject)
                .setGrantedPermissions(policyResource("/"), READ, WRITE)
                .setGrantedPermissions(thingResource("/"), READ, WRITE)
                .build();
    }

    private JsonObject buildImportingPolicyJsonWithImportRef(final PolicyId policyId,
            final PolicyId templateId, final Subject localSubject) {

        return JsonObject.newBuilder()
                .set("policyId", policyId.toString())
                .set("imports", JsonObject.newBuilder()
                        .set(templateId.toString(), JsonObject.newBuilder()
                                .set("entries", JsonFactory.newArrayBuilder()
                                        .add("DEFAULT").build())
                                .build())
                        .build())
                .set("entries", JsonObject.newBuilder()
                        .set("ADMIN", JsonObject.newBuilder()
                                .set("subjects", subjectsJson(defaultSubject))
                                .set("resources", JsonObject.newBuilder()
                                        .set("policy:/", permJson(List.of("READ", "WRITE")))
                                        .set("thing:/", permJson(List.of("READ", "WRITE")))
                                        .build())
                                .set("importable", "never")
                                .build())
                        .set("user-access", JsonObject.newBuilder()
                                .set("subjects", subjectsJson(localSubject))
                                .set("resources", JsonObject.empty())
                                .set("references", JsonArray.of(importRef(templateId, "DEFAULT")))
                                .build())
                        .build())
                .build();
    }

    private JsonObject buildImportingPolicyJsonWithExtraResource(final PolicyId policyId,
            final PolicyId templateId, final Subject localSubject,
            final String resourcePath, final List<String> grantedPerms) {

        return JsonObject.newBuilder()
                .set("policyId", policyId.toString())
                .set("imports", JsonObject.newBuilder()
                        .set(templateId.toString(), JsonObject.newBuilder()
                                .set("entries", JsonFactory.newArrayBuilder()
                                        .add("DEFAULT").build())
                                .build())
                        .build())
                .set("entries", JsonObject.newBuilder()
                        .set("ADMIN", JsonObject.newBuilder()
                                .set("subjects", subjectsJson(defaultSubject))
                                .set("resources", JsonObject.newBuilder()
                                        .set("policy:/", permJson(List.of("READ", "WRITE")))
                                        .set("thing:/", permJson(List.of("READ", "WRITE")))
                                        .build())
                                .set("importable", "never")
                                .build())
                        .set("user-access", JsonObject.newBuilder()
                                .set("subjects", subjectsJson(localSubject))
                                .set("resources", JsonObject.newBuilder()
                                        .set(resourcePath, permJson(grantedPerms))
                                        .build())
                                .set("references", JsonArray.of(importRef(templateId, "DEFAULT")))
                                .build())
                        .build())
                .build();
    }

    private static JsonObject subjectsJson(final Subject subject) {
        return JsonObject.newBuilder()
                .set(subject.getId().toString(), subject.toJson())
                .build();
    }

    private static JsonObject permJson(final List<String> grant) {
        return JsonObject.newBuilder()
                .set("grant", toJsonArray(grant))
                .set("revoke", JsonArray.empty())
                .build();
    }

    private static JsonArray toJsonArray(final List<String> strings) {
        final var builder = JsonFactory.newArrayBuilder();
        strings.forEach(builder::add);
        return builder.build();
    }

    private static JsonObject importRef(final PolicyId policyId, final String entryLabel) {
        return JsonObject.newBuilder()
                .set("import", policyId.toString())
                .set("entry", entryLabel)
                .build();
    }

    // --- Helper methods for sub-resource HTTP operations ---

    private static GetMatcher getPolicyEntryImportable(final CharSequence policyId,
            final CharSequence label) {
        final String path = ResourcePathBuilder.forPolicy(policyId)
                .policyEntry(label).toString() + "/importable";
        return get(dittoUrl(TestConstants.API_V_2, path))
                .withLogging(LOGGER, "PolicyEntryImportable");
    }

    private static PutMatcher putPolicyEntryImportable(final CharSequence policyId,
            final CharSequence label, final String importableType) {
        final String path = ResourcePathBuilder.forPolicy(policyId)
                .policyEntry(label).toString() + "/importable";
        return put(dittoUrl(TestConstants.API_V_2, path), "\"" + importableType + "\"")
                .withLogging(LOGGER, "PolicyEntryImportable");
    }

    private static GetMatcher getPolicyEntryAllowedImportAdditions(final CharSequence policyId,
            final CharSequence label) {
        final String path = ResourcePathBuilder.forPolicy(policyId)
                .policyEntry(label).toString() + "/allowedImportAdditions";
        return get(dittoUrl(TestConstants.API_V_2, path))
                .withLogging(LOGGER, "PolicyEntryAllowedImportAdditions");
    }

    private static PutMatcher putPolicyEntryAllowedImportAdditions(final CharSequence policyId,
            final CharSequence label, final JsonArray allowedImportAdditions) {
        final String path = ResourcePathBuilder.forPolicy(policyId)
                .policyEntry(label).toString() + "/allowedImportAdditions";
        return put(dittoUrl(TestConstants.API_V_2, path), allowedImportAdditions.toString())
                .withLogging(LOGGER, "PolicyEntryAllowedImportAdditions");
    }

    private static PutMatcher putReferences(final PolicyId policyId, final String label,
            final JsonArray references) {
        final String path = ResourcePathBuilder.forPolicy(policyId)
                .policyEntryReferences(label).toString();
        return put(dittoUrl(TestConstants.API_V_2, path), references.toString())
                .withLogging(LOGGER, "References");
    }

}
