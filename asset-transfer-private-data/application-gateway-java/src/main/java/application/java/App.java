 
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
import java.util.concurrent.TimeUnit;

import org.hyperledger.fabric.client.CallOption;
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

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

public class App {

	private static final String mspID = "Org1MSP";
	private static final String channelName = "mychannel";
	private static final String chaincodeName = "basic";

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

	public static void main(String[] args)
			throws InterruptedException, IOException, CertificateException, InvalidKeyException, Exception {

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

			// Initialize a set of asset data on the ledger using the chaincode 'InitLedger'
			// function.
			initLedger(contract);

			// Return all the current assets on the ledger.
			getAllAssets(contract);

			// Create a new asset on the ledger.
			createAsset(contract);

			// Update an existing asset asynchronously.
			transferAssetAsync(contract);

			// Get the asset details by assetID.
			readAssetById(contract);

			// Update an asset which does not exist.
			updateNonExistentAsset(contract);

		} finally {
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
	
	/**
	 * This type of transaction would typically only be run once by an application
	 * the first time it was started after its initial deployment. A new version of
	 * the chaincode deployed later would likely not need to run an "init" function.
	 */
	private static void initLedger(Contract contract) throws GatewayException, CommitException {

		System.out.println(
				"\n" + "--> Submit Transaction: InitLedger, function creates the initial set of assets on the ledger");
		contract.submitTransaction("InitLedger");
		System.out.println("*** Transaction committed successfully" + "\n");

	}

	/**
	 * Evaluate a transaction to query ledger state.
	 */
	private static void getAllAssets(Contract contract) throws GatewayException, CommitException {

		System.out.println(
				"\n" + "--> Evaluate Transaction: GetAllAssets, function returns all the current assets on the ledger");
		byte[] result = contract.evaluateTransaction("GetAllAssets");
		System.out.println("*** Result: " + prettyJson(result));

	}

	/**
	 * Submit a transaction synchronously, blocking until it has been committed to
	 * the ledger.
	 */
	private static void createAsset(Contract contract) throws GatewayException, CommitException {

		System.out.println("\n"
				+ "--> Submit Transaction: CreateAsset, creates new asset with ID, Color, Size, Owner and AppraisedValue arguments");

		contract.submitTransaction("CreateAsset", assetId, "yellow", "5", "Tom", "1300");

		System.out.println("*** Transaction committed successfully");
	}

	/**
	 * Submit transaction asynchronously, allowing the application to process the
	 * smart contract response (e.g. update a UI) while waiting for the commit
	 * notification.
	 */
	private static void transferAssetAsync(Contract contract) throws GatewayException, CommitException {

		System.out.println("\n" + "--> Async Submit Transaction: TransferAsset, updates existing asset owner");
		SubmittedTransaction commit = contract.newProposal("TransferAsset").addArguments(assetId, "Saptha").build()
				.endorse().submitAsync();

		byte[] result = commit.getResult();
		String oldOwner = new String(result, StandardCharsets.UTF_8);

		System.out.println(
				"*** Successfully submitted transaction to transfer ownership from " + oldOwner + " to Saptha");
		System.out.println("*** Waiting for transaction commit");
		Status status = commit.getStatus();
		if (!status.isSuccessful()) {
			throw new RuntimeException("Transaction " + status.getTransactionId() + " failed to commit with status code "
					+ status.getCode());

		}
		System.out.println("*** Transaction committed successfully");

	}

	private static void readAssetById(Contract contract) throws GatewayException, CommitException {

		System.out.println("\n" + "--> Evaluate Transaction: ReadAsset, function returns asset attributes");
		byte[] evaluateResult = contract.evaluateTransaction("ReadAsset", assetId);
		System.out.println("*** Result:" + prettyJson(evaluateResult));
	}

	/**
	 * submitTransaction() will throw an error containing details of any error
	 * responses from the smart contract.
	 */
	private static void updateNonExistentAsset(Contract contract) throws GatewayException, CommitException {

		try {
			System.out.println("\n"
					+ "--> Submit Transaction: UpdateAsset asset70, asset70 does not exist and should return an error");
			contract.submitTransaction("UpdateAsset", "asset70", "blue", "5", "Tomoko", "300");
			System.out.println("******** FAILED to return an error");

		} catch (EndorseException | SubmitException | CommitStatusException e) {
			System.out.println("*** Successfully caught the error: " + "\n");
			e.printStackTrace();
			if (!e.getDetails().isEmpty()) {
				System.out.println("\n" + "Error Details: ");
				for (ErrorDetail detail : e.getDetails()) {
					System.out.println("\n" + "address: " + detail.getAddress() + "\n" + "mspId: " + detail.getMspId()
							+ "\n" + "message:" + detail.getMessage() + "\n");
				}
			}
			System.out.println("Transaction ID: " + e.getTransactionId());
			
		} catch (CommitException e) {
			System.out.println("*** Successfully caught the error: " + e);
			e.printStackTrace();
			System.out.println("Transaction ID: " + e.getTransactionId() + " status code: " + e.getCode());
		}
	}

}