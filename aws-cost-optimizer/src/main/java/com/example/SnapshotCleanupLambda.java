package com.example;

import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DeleteSnapshotRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Snapshot;
import software.amazon.awssdk.services.ec2.model.VolumeAttachment;

/**
 * Deletes self-owned EBS snapshots that are no longer useful: - snapshots with
 * no source volume reference at all, OR - snapshots whose source volume still
 * exists but has no attachments, OR - snapshots whose source volume has been
 * deleted entirely.
 *
 * Rule: as soon as a snapshot matches any condition above, it is deleted
 * immediately - no further checks, no confirmation step.
 */
public class SnapshotCleanupLambda implements RequestHandler<Map<String, Object>, String> {

	 // Created once per Lambda execution environment and reused on warm starts.
	// instead of opening a fresh HTTP connection pool on every invocation.
	private final Ec2Client ec2 = Ec2Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build();

	@Override
	public String handleRequest(Map<String, Object> input, Context context) {
		LambdaLogger logger = context.getLogger();
		int deletedCount = 0;

		// 1. Get every snapshot this account owns.
				// Note: DescribeSnapshots is paginated (~1000 results per page by
				// default). On accounts with very large snapshot counts you'd need to
				// loop using the response's NextToken to catch everything.
		DescribeSnapshotsResponse snapshotsResponse = ec2
				.describeSnapshots(DescribeSnapshotsRequest.builder().ownerIds("self").build());
		
		// 2. Walk every snapshot and delete the stale ones immediately on match.
		for (Snapshot snapshot : snapshotsResponse.snapshots()) {
			String snapshotId = snapshot.snapshotId();
			String volumeId = snapshot.volumeId();

			// Case A: the snapshot has no source volume reference at all.
			if (volumeId == null || volumeId.isEmpty()) {
				deleteSnapShots(snapshotId, "it is not attached to any volume", logger);
				deletedCount++;
				continue;
			}
			try {
				// Case B: the source volume still exists - check whether it
				// actually has any attachments (i.e. is plugged into an instance).
				DescribeVolumesResponse volumeResponse = ec2
						.describeVolumes(DescribeVolumesRequest
								.builder()
								.volumeIds(volumeId)
								.build());
				List<VolumeAttachment> attachments = volumeResponse
						.volumes()
						.get(0)
						.attachments();
				if (attachments.isEmpty()) {
					deleteSnapShots(snapshotId,
							"its source volume (" + volumeId + ") is not attached to any instance", logger);
					deletedCount++;
				}
			} catch (Ec2Exception e) {
				// Case C: the source volume has been deleted entirely. AWS
				// signals this via an exception rather than an empty result
				if ("InvalidVolume.NotFound".equals(e.awsErrorDetails().errorCode())) {
					deleteSnapShots(snapshotId, "its source volume (" + volumeId + ") no longer exists", logger);
					deletedCount++;
				} else {
					// Any other EC2 error (throttling, permissions, etc.) - log
					// and move on rather than letting one bad snapshot abort
					// the whole run.
					logger.log("Skipped snapshots " + snapshotId + " -  error checking volume " + volumeId + ": "
							+ e.awsErrorDetails().errorMessage() + "\n ");
				}
			}
		}
		String summary = "Snapshot cleanup complete. Deleted " + deletedCount + " stale snapshot(s).";
		logger.log(summary + "\n");
		return summary;
	}
	// Issues the actual delete call for a single snapshot and logs the
		// outcome. Centralized here so every deletion path (Case A/B/C above)
		// logs in the same format.
	private void deleteSnapShots(String snapshotId, String reason, LambdaLogger logger) {
		try {
			ec2.deleteSnapshot(DeleteSnapshotRequest.builder().snapshotId(snapshotId).build());
			logger.log("Deleted EBS snapshot " + snapshotId + " because " + reason + ".\n");
		} catch (Ec2Exception e) {
			logger.log("Failed to delete snapshot " + snapshotId + ": " + e.awsErrorDetails().errorMessage() + "\n");
		}

	}

}
