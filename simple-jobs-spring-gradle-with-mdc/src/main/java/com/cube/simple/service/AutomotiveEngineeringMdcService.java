package com.cube.simple.service;

import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class AutomotiveEngineeringMdcService {
	private static final Map<String, AutomotiveProcess> AUTOMOTIVE_PROCESSES = Map.of(
			"system", new AutomotiveProcess(
					"battery-thermal-cycle",
					"ev-platform-alpha",
					"dyno-room-2",
					"bms-r6.3",
					"sil-homologation",
					"solid-state-pack",
					"regen-profile-a"),
			"manager", new AutomotiveProcess(
					"adas-scenario-replay",
					"autonomous-sedan-mule",
					"proving-ground-west",
					"adas-r12.1",
					"hardware-in-loop",
					"vision-radar-fusion",
					"lane-change-suite"),
			"operator", new AutomotiveProcess(
					"chassis-vibration-check",
					"hybrid-suv-beta",
					"nvh-bench-5",
					"vcu-r4.8",
					"pre-track-validation",
					"adaptive-damper-kit",
					"rough-road-profile"));

	public Map<String, String> createContext(String jobName, String runId) {
		AutomotiveProcess process = AUTOMOTIVE_PROCESSES.getOrDefault(jobName, AutomotiveProcess.defaultProcess());
		return Map.of(
				"engineeringDomain", "automotive-engineering",
				"vehicleTestStage", process.vehicleTestStage(),
				"vehiclePlatform", process.vehiclePlatform(),
				"testFacility", process.testFacility(),
				"controllerRelease", process.controllerRelease(),
				"validationMode", process.validationMode(),
				"componentVariant", process.componentVariant(),
				"driveCycleProfile", process.driveCycleProfile(),
				"vehicleExperimentId", "veh-" + jobName + "-" + runId.substring(0, 8));
	}

	private record AutomotiveProcess(
			String vehicleTestStage,
			String vehiclePlatform,
			String testFacility,
			String controllerRelease,
			String validationMode,
			String componentVariant,
			String driveCycleProfile) {
		private static AutomotiveProcess defaultProcess() {
			return new AutomotiveProcess(
					"lab-screening",
					"concept-vehicle-00",
					"integration-bay",
					"baseline-r1.0",
					"bench-validation",
					"generic-control-unit",
					"urban-reference");
		}
	}
}
