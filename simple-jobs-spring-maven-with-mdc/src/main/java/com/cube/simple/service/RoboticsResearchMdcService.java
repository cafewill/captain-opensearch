package com.cube.simple.service;

import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class RoboticsResearchMdcService {
	private static final Map<String, RoboticsProcess> ROBOTICS_PROCESSES = Map.of(
			"system", new RoboticsProcess(
					"sensor-calibration",
					"perception-lab",
					"cell-a1",
					"arm-vision-07",
					"adaptive-grasping",
					"lidar-camera-rig",
					"supervised",
					"twin-2.4.1",
					"day"),
			"manager", new RoboticsProcess(
					"trajectory-planning",
					"motion-intelligence-lab",
					"cell-b2",
					"mobile-manipulator-03",
					"multi-agent-orchestration",
					"titanium-test-jig",
					"guarded-autonomy",
					"twin-2.4.1",
					"day"),
			"operator", new RoboticsProcess(
					"gripper-validation",
					"field-robotics-lab",
					"cell-c3",
					"inspection-rover-11",
					"resilient-end-effector",
					"composite-gripper-v5",
					"safety-review",
					"twin-2.4.2",
					"night"));

	public Map<String, String> createContext(String jobName, String runId) {
		RoboticsProcess process = ROBOTICS_PROCESSES.getOrDefault(jobName, RoboticsProcess.defaultProcess());
		return Map.ofEntries(
				Map.entry("labDomain", "robotics-research"),
				Map.entry("researchLab", process.researchLab()),
				Map.entry("robotCell", process.robotCell()),
				Map.entry("robotUnitId", process.robotUnitId()),
				Map.entry("processStage", process.processStage()),
				Map.entry("researchProgram", process.researchProgram()),
				Map.entry("sampleType", process.sampleType()),
				Map.entry("safetyMode", process.safetyMode()),
				Map.entry("digitalTwinVersion", process.digitalTwinVersion()),
				Map.entry("operatorShift", process.operatorShift()),
				Map.entry("experimentId", "exp-" + jobName + "-" + runId.substring(0, 8)));
	}

	private record RoboticsProcess(
			String processStage,
			String researchLab,
			String robotCell,
			String robotUnitId,
			String researchProgram,
			String sampleType,
			String safetyMode,
			String digitalTwinVersion,
			String operatorShift) {
		private static RoboticsProcess defaultProcess() {
			return new RoboticsProcess(
					"lab-observation",
					"robotics-core-lab",
					"cell-z0",
					"prototype-00",
					"baseline-observability",
					"generic-fixture",
					"supervised",
					"twin-2.4.0",
					"day");
		}
	}
}
