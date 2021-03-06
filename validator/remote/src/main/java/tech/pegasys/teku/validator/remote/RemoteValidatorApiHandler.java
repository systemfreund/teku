/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.validator.remote;

import static java.util.Collections.emptyMap;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.api.response.v1.beacon.ValidatorResponse;
import tech.pegasys.teku.api.response.v1.validator.AttesterDuty;
import tech.pegasys.teku.api.response.v1.validator.ProposerDuty;
import tech.pegasys.teku.api.schema.BLSPubKey;
import tech.pegasys.teku.api.schema.ValidatorDutiesRequest;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.datastructures.genesis.GenesisData;
import tech.pegasys.teku.datastructures.operations.Attestation;
import tech.pegasys.teku.datastructures.operations.AttestationData;
import tech.pegasys.teku.datastructures.operations.SignedAggregateAndProof;
import tech.pegasys.teku.datastructures.state.Fork;
import tech.pegasys.teku.datastructures.validator.SubnetSubscription;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.validator.api.AttesterDuties;
import tech.pegasys.teku.validator.api.CommitteeSubscriptionRequest;
import tech.pegasys.teku.validator.api.ProposerDuties;
import tech.pegasys.teku.validator.api.SendSignedBlockResult;
import tech.pegasys.teku.validator.api.ValidatorApiChannel;
import tech.pegasys.teku.validator.api.ValidatorDuties;
import tech.pegasys.teku.validator.remote.apiclient.ValidatorRestApiClient;

public class RemoteValidatorApiHandler implements ValidatorApiChannel {

  private static final Logger LOG = LogManager.getLogger();
  static final int MAX_PUBLIC_KEY_BATCH_SIZE = 10;

  private final ValidatorRestApiClient apiClient;
  private final AsyncRunner asyncRunner;

  public RemoteValidatorApiHandler(
      final ValidatorRestApiClient apiClient, final AsyncRunner asyncRunner) {
    this.apiClient = apiClient;
    this.asyncRunner = asyncRunner;
  }

  @Override
  public SafeFuture<Optional<Fork>> getFork() {
    return asyncRunner.runAsync(
        () ->
            apiClient
                .getFork()
                .map(
                    result ->
                        new Fork(result.previous_version, result.current_version, result.epoch)));
  }

  @Override
  public SafeFuture<Optional<GenesisData>> getGenesisData() {
    return asyncRunner.runAsync(
        () ->
            apiClient
                .getGenesis()
                .map(
                    response ->
                        new GenesisData(
                            response.data.genesisTime, response.data.genesisValidatorsRoot)));
  }

  @Override
  public SafeFuture<Map<BLSPublicKey, Integer>> getValidatorIndices(
      final List<BLSPublicKey> publicKeys) {
    if (publicKeys.isEmpty()) {
      return SafeFuture.completedFuture(emptyMap());
    }
    return asyncRunner.runAsync(
        () -> {
          final Map<BLSPublicKey, Integer> indices = new HashMap<>();
          for (int i = 0; i < publicKeys.size(); i += MAX_PUBLIC_KEY_BATCH_SIZE) {
            final List<BLSPublicKey> batch =
                publicKeys.subList(i, Math.min(publicKeys.size(), i + MAX_PUBLIC_KEY_BATCH_SIZE));
            requestValidatorIndices(batch).ifPresent(indices::putAll);
          }
          return indices;
        });
  }

  private Optional<Map<BLSPublicKey, Integer>> requestValidatorIndices(
      final List<BLSPublicKey> batch) {
    return apiClient
        .getValidators(
            batch.stream()
                .map(key -> key.toBytesCompressed().toHexString())
                .collect(Collectors.toList()))
        .map(this::convertToValidatorIndexMap);
  }

  private Map<BLSPublicKey, Integer> convertToValidatorIndexMap(
      final List<ValidatorResponse> validatorResponses) {
    return validatorResponses.stream()
        .collect(
            Collectors.<ValidatorResponse, BLSPublicKey, Integer>toMap(
                response -> response.validator.pubkey.asBLSPublicKey(),
                response -> response.index.intValue()));
  }

  @Override
  public SafeFuture<Optional<List<ValidatorDuties>>> getDuties(
      final UInt64 epoch, final Collection<BLSPublicKey> publicKeys) {
    if (publicKeys.isEmpty()) {
      return SafeFuture.completedFuture(Optional.of(Collections.emptyList()));
    }

    return asyncRunner.runAsync(
        () -> {
          final List<BLSPubKey> blsPubKeys =
              publicKeys.stream().map(BLSPubKey::new).collect(Collectors.toList());
          final ValidatorDutiesRequest validatorDutiesRequest =
              new ValidatorDutiesRequest(epoch, blsPubKeys);

          final List<ValidatorDuties> validatorDuties =
              apiClient.getDuties(validatorDutiesRequest).stream()
                  .map(this::mapToApiValidatorDuties)
                  .collect(Collectors.toList());

          return Optional.of(validatorDuties);
        });
  }

  @Override
  public SafeFuture<Optional<List<AttesterDuties>>> getAttestationDuties(
      final UInt64 epoch, final Collection<Integer> validatorIndexes) {
    return asyncRunner.runAsync(
        () -> {
          final List<AttesterDuties> duties =
              apiClient.getAttestationDuties(epoch, validatorIndexes).stream()
                  .map(this::mapToApiAttesterDuties)
                  .collect(Collectors.toList());

          return Optional.of(duties);
        });
  }

  @Override
  public SafeFuture<Optional<List<ProposerDuties>>> getProposerDuties(final UInt64 epoch) {
    return asyncRunner.runAsync(
        () -> {
          final List<ProposerDuties> duties =
              apiClient.getProposerDuties(epoch).stream()
                  .map(this::mapToProposerDuties)
                  .collect(Collectors.toList());

          return Optional.of(duties);
        });
  }

  private ProposerDuties mapToProposerDuties(final ProposerDuty proposerDuty) {
    return new ProposerDuties(
        proposerDuty.pubkey.asBLSPublicKey(),
        proposerDuty.validatorIndex.intValue(),
        proposerDuty.slot);
  }

  private AttesterDuties mapToApiAttesterDuties(final AttesterDuty attesterDuty) {
    return new AttesterDuties(
        attesterDuty.pubkey.asBLSPublicKey(),
        attesterDuty.validatorIndex.intValue(),
        attesterDuty.committeeLength.intValue(),
        attesterDuty.committeeIndex.intValue(),
        attesterDuty.committeesAtSlot.intValue(),
        attesterDuty.validatorCommitteeIndex.intValue(),
        attesterDuty.slot);
  }

  private ValidatorDuties mapToApiValidatorDuties(
      final tech.pegasys.teku.api.schema.ValidatorDuties schemaValidatorDuties) {
    return ValidatorDuties.withDuties(
        schemaValidatorDuties.validator_pubkey.asBLSPublicKey(),
        schemaValidatorDuties.validator_index,
        schemaValidatorDuties.attestation_committee_index,
        schemaValidatorDuties.attestation_committee_position,
        schemaValidatorDuties.aggregator_modulo,
        schemaValidatorDuties.block_proposal_slots,
        schemaValidatorDuties.attestation_slot);
  }

  @Override
  public SafeFuture<Optional<Attestation>> createUnsignedAttestation(
      final UInt64 slot, final int committeeIndex) {
    return asyncRunner.runAsync(
        () ->
            apiClient
                .createUnsignedAttestation(slot, committeeIndex)
                .map(tech.pegasys.teku.api.schema.Attestation::asInternalAttestation));
  }

  @Override
  public SafeFuture<Optional<AttestationData>> createAttestationData(
      final UInt64 slot, final int committeeIndex) {
    return asyncRunner.runAsync(
        () ->
            apiClient
                .createAttestationData(slot, committeeIndex)
                .map(tech.pegasys.teku.api.schema.AttestationData::asInternalAttestationData));
  }

  @Override
  public void sendSignedAttestation(final Attestation attestation) {
    final tech.pegasys.teku.api.schema.Attestation schemaAttestation =
        new tech.pegasys.teku.api.schema.Attestation(attestation);

    asyncRunner
        .runAsync(() -> apiClient.sendSignedAttestation(schemaAttestation))
        .finish(error -> LOG.error("Failed to send signed attestation", error));
  }

  @Override
  public void sendSignedAttestation(
      final Attestation attestation, final Optional<Integer> validatorIndex) {
    sendSignedAttestation(attestation);
  }

  @Override
  public SafeFuture<Optional<BeaconBlock>> createUnsignedBlock(
      final UInt64 slot, final BLSSignature randaoReveal, final Optional<Bytes32> graffiti) {
    return asyncRunner.runAsync(
        () -> {
          final tech.pegasys.teku.api.schema.BLSSignature schemaBLSSignature =
              new tech.pegasys.teku.api.schema.BLSSignature(randaoReveal);

          return apiClient
              .createUnsignedBlock(slot, schemaBLSSignature, graffiti)
              .map(tech.pegasys.teku.api.schema.BeaconBlock::asInternalBeaconBlock);
        });
  }

  @Override
  public SafeFuture<SendSignedBlockResult> sendSignedBlock(final SignedBeaconBlock block) {
    return asyncRunner.runAsync(
        () -> apiClient.sendSignedBlock(new tech.pegasys.teku.api.schema.SignedBeaconBlock(block)));
  }

  @Override
  public SafeFuture<Optional<Attestation>> createAggregate(final Bytes32 attestationHashTreeRoot) {
    return asyncRunner.runAsync(
        () ->
            apiClient
                .createAggregate(attestationHashTreeRoot)
                .map(tech.pegasys.teku.api.schema.Attestation::asInternalAttestation));
  }

  @Override
  public void sendAggregateAndProof(final SignedAggregateAndProof aggregateAndProof) {
    asyncRunner
        .runAsync(
            () ->
                apiClient.sendAggregateAndProof(
                    new tech.pegasys.teku.api.schema.SignedAggregateAndProof(aggregateAndProof)))
        .finish(error -> LOG.error("Failed to send aggregate and proof", error));
  }

  @Override
  public void subscribeToBeaconCommittee(final List<CommitteeSubscriptionRequest> requests) {
    asyncRunner
        .runAsync(() -> apiClient.subscribeToBeaconCommittee(requests))
        .finish(
            error -> LOG.error("Failed to subscribe to beacon committee for aggregation", error));
  }

  @Override
  public void subscribeToPersistentSubnets(final Set<SubnetSubscription> subnetSubscriptions) {
    final Set<tech.pegasys.teku.api.schema.SubnetSubscription> schemaSubscriptions =
        subnetSubscriptions.stream()
            .map(
                s ->
                    new tech.pegasys.teku.api.schema.SubnetSubscription(
                        s.getSubnetId(), s.getUnsubscriptionSlot()))
            .collect(Collectors.toSet());

    asyncRunner
        .runAsync(() -> apiClient.subscribeToPersistentSubnets(schemaSubscriptions))
        .finish(error -> LOG.error("Failed to subscribe to persistent subnets", error));
  }
}
