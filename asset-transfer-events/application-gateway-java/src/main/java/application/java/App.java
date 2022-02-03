
/*
 * Copyright IBM Corp. All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

// Running TestApp: 
// gradle runApp 

package application.java;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.hyperledger.fabric.client.CallOption;
import org.hyperledger.fabric.client.ChaincodeEvent;
import org.hyperledger.fabric.client.ChaincodeEventsRequest;
import org.hyperledger.fabric.client.CloseableIterator;
import org.hyperledger.fabric.client.CommitException;
import org.hyperledger.fabric.client.CommitStatusException;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.client.Network;
import org.hyperledger.fabric.client.SubmitException;
import org.hyperledger.fabric.client.SubmittedTransaction;
import org.hyperledger.fabric.client.Status;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Identity;
import org.hyperledger.fabric.client.identity.Signer;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;
import org.hyperledger.fabric.protos.gateway.ErrorDetail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.grpc.ManagedChannel;
import io.grpc.Context;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

public class App {

	private static final String mspID = "Org1MSP";
	private static final String channelName = "mychannel";
	private static final String chaincodeName = "event";

	public static String assetId = "asset" + Instant.now().toEpochMilli();

	// Path to crypto materials.
	private static final Path cryptoPath = Paths.get("..", "..", "test-network", "organizations", "peerOrganizations",
			"org1.example.com");
	// Path to user certificate.
	private static final Path certPath = cryptoPath
			.resolve(Paths.get("users", "User1@org1.example.com", "msp", "signcerts", "cert.pem"));
	// Path to user private key directory.
	private static final Path keyPath = cryptoPath
			.resolve(Paths.get("users", "User1@org1.example.com", "msp", "keystore"));
	// Path to peer tls certificate.
	private static final Path tlsCertPath = cryptoPath
			.resolve(Paths.get("peers", "peer0.org1.example.com", "tls", "ca.crt"));

	// Gateway peer end point.
	public static String peerEndpoint = "localhost:7051";
	public static String overrideAuth = "peer0.org1.example.com";

	public static long firstBlockNumber;

	public static void main(String[] args) throws Exception {

		// The gRPC client connection should be shared by all Gateway connections to
		// this endpoint.
		ManagedChannel channel = newGrpcConnection();

		Gateway.Builder builder = Gateway.newInstance().identity(newIdentity()).signer(newSigner()).connection(channel)
				// Default timeouts for different gRPC calls
				.evaluateOptions(CallOption.deadlineAfter(5, TimeUnit.SECONDS))
				.endorseOptions(CallOption.deadlineAfter(15, TimeUnit.SECONDS))
				.submitOptions(CallOption.deadlineAfter(5, TimeUnit.SECONDS))
				.commitStatusOptions(CallOption.deadlineAfter(1, TimeUnit.MINUTES));

		try (Gateway gateway = builder.connect()) {

			// Get a network instance representing the channel where the smart contract is
			// deployed.
			Network network = gateway.getNetwork(channelName);

			// Get the smart contract from the network.
			Contract contract = network.getContract(chaincodeName);

			// Listen for events emitted by subsequent transactions

			try (AutoCloseable eventSession = startChaincodeEventListening(network)) {

				// ... Submitting transactions etc.
				firstBlockNumber = createAsset(contract);
				updateAsset(contract);
				transferAsset(contract);
				deleteAsset(contract);

			} // <-- eventSession gets automatically closed here

			// Replay events from the block containing the first transaction
			replayChaincodeEvents(network, firstBlockNumber);

		} finally {
			// eventSession.close();
			channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	private static ManagedChannel newGrpcConnection() throws IOException, CertificateException {
		Reader tlsCertReader = Files.newBufferedReader(tlsCertPath);
		X509Certificate tlsCert = Identities.readX509Certificate(tlsCertReader);

		return NettyChannelBuilder.forTarget(peerEndpoint)
				.sslContext(GrpcSslContexts.forClient().trustManager(tlsCert).build()).overrideAuthority(overrideAuth)
				.build();
	}

	private static Identity newIdentity() throws IOException, CertificateException {
		Reader certReader = Files.newBufferedReader(certPath);
		X509Certificate certificate = Identities.readX509Certificate(certReader);

		return new X509Identity(mspID, certificate);
	}

	private static Signer newSigner() throws IOException, InvalidKeyException {
		File dir = new File(keyPath.toString());
		File[] listOfFiles = dir.listFiles();
		Path path = Paths.get(listOfFiles[0].getPath());
		Reader keyReader = Files.newBufferedReader(path);
		PrivateKey privateKey = Identities.readPrivateKey(keyReader);

		return Signers.newPrivateKeySigner(privateKey);
	}

	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	private static String prettyJson(byte[] json) {
		return prettyJson(new String(json, StandardCharsets.UTF_8));
	}

	private static String prettyJson(String json) {
		JsonElement parsedJson = JsonParser.parseString(json);
		return gson.toJson(parsedJson);
	}

	private static CloseableIterator<ChaincodeEvent> startChaincodeEventListening(Network network)
			throws GatewayException, CommitException, ExecutionException, InterruptedException {

		CloseableIterator<ChaincodeEvent> eventsIter = network.getChaincodeEvents(chaincodeName);

		try {

			System.out.println("\n" + "*** Start chaincode event listening" + "\n");

			CompletableFuture<ChaincodeEvent> readEvent = CompletableFuture.supplyAsync(eventsIter::next);

			eventsIter.forEachRemaining(event -> {
				System.out.println(
						"<-- Chaincode event received: " + event.getEventName() + prettyJson(event.getPayload()));
			});

		} catch (Exception e) {
			e.printStackTrace(System.out);
		} finally {
			return eventsIter;
		}
	}

//	pubic void close() {
//		close.run();
//	}
//	
	private static long createAsset(Contract contract) {

		System.out.println("\n" + "--> Submit transaction: CreateAsset, " + assetId
				+ " owned by Sam with appraised value 100" + "\n");

		try {
			SubmittedTransaction commit = contract.newProposal("CreateAsset")
					.addArguments(assetId, "blue", "10", "Sam", "100").build().endorse().submitAsync();

			Status status = commit.getStatus();
			firstBlockNumber = status.getBlockNumber();
			if (status == null) {
				System.out.println("failed to get transaction commit status: " + status);
			}
			if (!status.isSuccessful()) {
				throw new RuntimeException("failed to commit transaction with status code " + status.getCode());

			}

			System.out.println("\n" + "*** CreateAsset committed successfully" + "\n");
		} catch (EndorseException | SubmitException | CommitStatusException e) {

			e.printStackTrace(System.out);
			if (!e.getDetails().isEmpty()) {
				System.out.println("\n" + "Error Details: ");
				for (ErrorDetail detail : e.getDetails()) {
					System.out.println("address: " + detail.getAddress() + ", mspId: " + detail.getMspId()
							+ ", message: " + detail.getMessage());
				}
			}
		}
		return firstBlockNumber;

	}

	private static void updateAsset(Contract contract)
			throws EndorseException, SubmitException, CommitStatusException, CommitException {

		System.out.println(
				"\n" + "--> Submit transaction: UpdateAsset, " + assetId + "update appraised value to 200" + "\n");

		contract.submitTransaction("UpdateAsset", assetId, "blue", "10", "Sam", "200");

		System.out.println("\n*** UpdateAsset committed successfully\n");
	}

	private static void transferAsset(Contract contract)
			throws EndorseException, SubmitException, CommitStatusException, CommitException {

		System.out.println("\n" + "--> Submit transaction: TransferAsset, " + assetId + " to Mary" + "\n");

		contract.submitTransaction("TransferAsset", assetId, "Mary");

		System.out.println("\n" + "*** TransferAsset committed successfully" + "\n");

	}

	private static void deleteAsset(Contract contract)
			throws EndorseException, SubmitException, CommitStatusException, CommitException {

		System.out.println("\n" + "--> Submit transaction: DeleteAsset, " + assetId + "\n");

		contract.submitTransaction("DeleteAsset", assetId);

		System.out.println("\n" + "*** DeleteAsset committed successfully" + "\n");

	}

	private static void replayChaincodeEvents(Network network, long startBlock) {

		System.out.println("\n" + "*** Start chaincode event replay" + "\n");

		ChaincodeEventsRequest request = network.newChaincodeEventsRequest(chaincodeName).startBlock(startBlock)
				.build();
		try (CloseableIterator<ChaincodeEvent> events = request.getEvents()) {

			// try (CloseableIterator<ChaincodeEvent> events =
			// network.getChaincodeEvents(chaincodeName,startBlock)) {
			// CompletableFuture<ChaincodeEvent> readEvent =
			// CompletableFuture.supplyAsync(events::next);
			while (events.hasNext()) {
				// event = readEvent.orTimeout(10, TimeUnit.SECONDS).get();
				ChaincodeEvent event = events.next();
				if (event.getEventName() == "DeleteAsset") {

					System.exit(0);
				}
				System.out.println("Received event name: " + event.getEventName() + ", payload: "
						+ prettyJson(event.getPayload()) + ", txId: " + event.getTransactionId());
			}
		}
	}
	/**
	 * 
	 * Sample Output : Start chaincode event listening
	 * 
	 * --> Submit transaction: CreateAsset, asset1643368539754 owned by Sam with
	 * appraised value 100
	 *** 
	 * CreateAsset committed successfully
	 * 
	 * --> Submit transaction: UpdateAsset, asset1643368539754 update appraised
	 * value to 200
	 * 
	 * <-- Chaincode event received: CreateAsset - { "ID": "asset1643368539754",
	 * "Color": "blue", "Size": "10", "Owner": "Sam", "AppraisedValue": "100" }
	 *** 
	 * UpdateAsset committed successfully
	 * 
	 * --> Submit transaction: TransferAsset, asset1643368539754 to Mary
	 * 
	 * <-- Chaincode event received: UpdateAsset - { "ID": "asset1643368539754",
	 * "Color": "blue", "Size": "10", "Owner": "Sam", "AppraisedValue": "200" }
	 *** 
	 * TransferAsset committed successfully
	 * 
	 * --> Submit transaction: DeleteAsset, asset1643368539754
	 * 
	 * <-- Chaincode event received: TransferAsset - { "ID": "asset1643368539754",
	 * "Color": "blue", "Size": "10", "Owner": "Mary", "AppraisedValue": "200" }
	 * 
	 * <-- Chaincode event received: DeleteAsset - { "ID": "asset1643368539754",
	 * "Color": "blue", "Size": "10", "Owner": "Mary", "AppraisedValue": "200" }
	 *** 
	 * DeleteAsset committed successfully
	 *** 
	 * Start chaincode event replay
	 * 
	 * <-- Chaincode event replayed: CreateAsset - { "ID": "asset1643368539754",
	 * "Color": "blue", "Size": "10", "Owner": "Sam", "AppraisedValue": "100" }
	 * 
	 * <-- Chaincode event replayed: UpdateAsset - { "ID": "asset1643368539754",
	 * "Color": "blue", "Size": "10", "Owner": "Sam", "AppraisedValue": "200" }
	 * 
	 * <-- Chaincode event replayed: TransferAsset - { "ID": "asset1643368539754",
	 * "Color": "blue", "Size": "10", "Owner": "Mary", "AppraisedValue": "200" }
	 * 
	 * <-- Chaincode event replayed: DeleteAsset - { "ID": "asset1643368539754",
	 * "Color": "blue", "Size": "10", "Owner": "Mary", "AppraisedValue": "200" }
	 * 
	 */
}