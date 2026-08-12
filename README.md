# ActorTrackAndDetect
Chat with Claude: gaps in research

Mapping each gap to established literature that tackles it directly:

1. Cross-agent track handoff / overlap-zone coordination (the dead SharedTargetInfo code)

Khan & Shah, "Consistent Labeling of Tracked Objects in Multiple Cameras with Overlapping Fields of View" (PAMI 2003) — canonical solution for handing off a track between sensors with overlapping FOV, exactly your drone-boundary problem.
Javed, Shafique, Shah, "Tracking Across Multiple Cameras with Disjoint Views" (ICCV 2003) — extends handoff to non-overlapping regions, relevant if you ever shrink the overlap zones.
2. Avoiding double-counting when two drones track the same target independently

Julier & Uhlmann, "General Decentralized Data Fusion with Covariance Intersection" (2001) — Covariance Intersection is the standard fix for fusing estimates with unknown correlation, which is precisely what happens when neighboring drones both spawn a TargetNode for the same object.
Chen, Arambel, Mehra, "Estimation Under Unknown Correlation: Covariance Intersection Revisited" (IEEE TAC 2002).
3. Decentralized/distributed Kalman filtering itself

Olfati-Saber, "Distributed Kalman Filter with Embedded Consensus Filters" (CDC 2005) and "Kalman-Consensus Filter" (2007) — actors reach a shared estimate via local message passing, closer to true decentralization than your current per-drone-isolated filters.
Durrant-Whyte & Stevens, "Data Fusion in Decentralized Sensing Networks" (2001) — foundational framework for why/how decentralized nodes fuse without a central hub (relevant since your code still has a half-built centralized-hub variant commented out).
4. Data association (your greedy nearest-neighbor-per-cell matching)

Bar-Shalom & Fortmann, Tracking and Data Association (1988) — the JPDA book; handles ambiguous/close targets your threshold-based matching would misassign.
Reid, "An Algorithm for Tracking Multiple Targets" (IEEE TAC 1979) — Multiple Hypothesis Tracking (MHT), the standard answer to crossing-trajectory ambiguity.
5. Multi-robot active target tracking with distributed sensors

Zhou & Roumeliotis, "Multi-robot Active Target Tracking with Combinations of Relative Observations" (IEEE T-RO 2011) — closest match to your drone-swarm setup: mobile agents, local sensing, relative observation sharing.
Zhao, Shin, Reich, "Information-Driven Dynamic Sensor Collaboration" (IEEE Signal Processing Mag. 2002) — sensor networks deciding when/what to share, relevant to your currently-absent sharing policy.
6. Track-to-track fusion / association across independently-run filters

Chong, Mori, Chang, "Distributed Multitarget Multisensor Tracking" (1990, in Bar-Shalom's Multitarget-Multisensor Tracking series) — the formal treatment of exactly the "two drones, two tracks, same target" problem you'd hit once handoff is wired up.
Highest-leverage read for your specific codebase: start with Khan & Shah (2003) for the handoff mechanism itself, then Julier & Uhlmann (2001) for the fusion math once two drones' TargetNodes need to be reconciled — those two papers cover the actual gap in isInOverlapZone/SharedTargetInfo.


## Limitations

Kalman filter is a reasonable baseline but not the state of the art, and it's worth noting it's actually a bit mismatched to this codebase's own motion model.

Where the current KF is mismatched to your own simulation
simulation.py generates sinusoidal/elliptical motion, but SNDKalmanEstimator.scala uses a linear constant-velocity model (A matrix has no acceleration term) and just inflates process noise Q to compensate (SNDKalmanEstimator.scala:85-113). That's a real gap between your motion generator and your filter's assumptions.

Better fits for what you actually have
For the motion-model mismatch (maneuvering/curved targets):

IMM (Interacting Multiple Model) filter — runs several motion models (constant velocity, constant turn, etc.) in parallel and blends them by likelihood. This is the standard classical answer to sinusoidal/maneuvering targets and is a much smaller change than rewriting your architecture.
UKF/EKF if you want a single nonlinear model instead of inflating Q on a linear one.
For the multi-target birth/death/association problem you've hand-rolled in TargetNode:
Your createNewTarget / TargetCleanupNotification / distance-threshold logic is manually reimplementing what Random Finite Set (RFS) filters do formally:

GM-PHD filter (Vo & Ma, 2006, building on Mahler's PHD theory) — tracks a distribution over an unknown, time-varying number of targets, natively handles target birth/death and clutter, without needing hand-coded activity timers and threshold checks. This is arguably the single most relevant "latest-generation" classical replacement for your current architecture, since it eliminates the ad hoc spawn/cleanup logic in SNDTarget.scala:124-154 by construction.
Labeled Multi-Bernoulli (LMB/GLMB) filters — successor to PHD that also maintains track identity/continuity, closer to what you'd want for consistent target IDs across frames.
For association under ambiguity (crossing paths, clutter):

JPDA / MHT (already mentioned) remain standard; PHD/GLMB filters above largely subsume this by avoiding hard association altogether.
Modern/learned methods (mostly for vision-based MOT, less relevant to your physics-sim setup):

SORT / DeepSORT / ByteTrack — note these still use a Kalman filter for motion prediction, just add a learned re-identification embedding for association. So "latest" here doesn't mean KF is obsolete — it means KF + learned appearance features.
Transformer-based end-to-end trackers (TrackFormer, MOTR) — state of the art on vision MOT benchmarks, but they need large labeled training sets and image input; overkill and a poor fit for a coordinate-only physics simulation like yours.
Recommendation for this project specifically
Kalman filter isn't wrong as a per-target smoother — keep it. The higher-value upgrade given your actual pain points (motion mismatch + hand-rolled birth/death) would be IMM if you want a minimal, drop-in fix, or a GM-PHD filter if you're open to replacing the TargetNode spawn/cleanup logic with a principled multi-target filter — that also happens to compose naturally with the track-handoff/fusion papers I mentioned earlier, since PHD filters are commonly distributed across sensor nodes in exactly the decentralized-fusion literature (Üney, Clark, Julier, "Distributed Fusion of PHD Filters via Exponential Mixture Densities", 2013) that matches your drone-swarm setup.

## Recent changes

Recent (2025–2026) work clusters into a few themes that map directly onto your project's gaps:

Distributed RFS-based fusion (successor to the PHD/GM-PHD idea I mentioned)
"Distributed Multi-Sensor Control for Multi-Target Tracking Using Adaptive Complementary Fusion for LMB Densities" — this is the direct modern successor to the classical decentralized-PHD literature: agents each maintain a Labeled Multi-Bernoulli (LMB) density and fuse them adaptively rather than exchanging raw tracks. This is the closest match to replacing your TargetNode spawn/cleanup + SharedTargetInfo handoff with a principled, currently-active research approach.

Decentralized swarm tracking with local sensing (structurally close to your drone-grid architecture)
"Visibility-aware Cooperative Aerial Tracking with Decentralized LiDAR-based Swarms" — drones use local LiDAR + mutual state estimation (each drone self-localizes and estimates neighbors' states, no central hub) to cooperatively track a target while maintaining line-of-sight, with collision-free coordination. Structurally this is very close to what your Drone/Sensor/neighbor-graph setup is trying to be, just with a real visibility/coordination objective layered on top instead of static grid partitioning.

Cross-drone identity association / re-identification (this is your dead isInOverlapZone/handoff problem, solved with modern re-ID)
"Identification and Association of Multiple Visually Identical Targets for Air–Ground Cooperative Systems" — uses Dempster-Shafer fusion combining projection-based and topology-based association to decide whether two agents' detections are the same target. Directly relevant to the double-counting problem when two drones independently spawn a TargetNode for one object.
"Multi-View, Multi-Target Tracking in Low-Altitude Scenes with UAV Involvement" (MVTL-UAV) — cross-view identity association for UAV-observed targets, tackling occlusion/appearance-similarity, the modern version of the "track-to-track fusion" literature.

Learned/adaptive control layered on top of tracking
"Real-time cooperative target tracking in cluttered environments using multiple drone swarms with adaptive fuzzy emotional learning" — combines fuzzy inference + neuro-emotional learning + distributed multi-agent coordination for trajectory prediction under clutter; representative of the trend toward learned motion prediction replacing/augmenting the constant-velocity Kalman assumption.

Surveys to anchor the field quickly
"UAV target tracking: a survey" (Springer, Artificial Intelligence Review, 2025) and "Recent Developments and Applications of Drone Swarm: Techniques, Strategies, and Challenges" (MDPI, 2026) are good current-state overviews if you want one document that maps the whole space before diving into any single thread above.

Bottom line relative to your codebase: the field has moved from "hand-coded threshold matching + Kalman" (what you have) toward (a) distributed RFS/LMB densities for principled multi-target birth/death/fusion, and (b) learned re-identification for the cross-agent handoff problem — both are essentially formalized, better-tested versions of the two things your code currently stubs out or leaves commented out.

Sources:

Distributed Multi-Sensor Control for Multi-Target Tracking Using Adaptive Complementary Fusion for LMB Densities
Visibility-aware Cooperative Aerial Tracking with Decentralized LiDAR-based Swarms
Identification and Association of Multiple Visually Identical Targets for Air–Ground Cooperative Systems
Multi-View, Multi-Target Tracking in Low-Altitude Scenes with UAV Involvement
Real-time cooperative target tracking in cluttered environments using multiple drone swarms with adaptive fuzzy emotional learning
UAV target tracking: a survey
Recent Developments and Applications of Drone Swarm: Techniques, Strategies, and Challenges

## Published related work
Computer vision (CVPR / IEEE Trans. — A*/reputed journal)
"Multi-Object Tracking Meets Moving UAV" — Liu, Li, Lu, He. CVPR 2022 (A*). UAVMOT: handles irregular motion from a moving camera platform with an adaptive motion filter — relevant if you ever move from ground-truth-coordinate replay to actual vision-based detection on your drones.

"Robust Multi-Drone Multi-Target Tracking to Resolve Target Occlusion: A Benchmark" — Liu, Shang, Li, et al. IEEE Transactions on Multimedia, 2023. Introduces the MDMT dataset (88 sequences, 2.2M boxes) and a cross-drone identity-association network (MIA-Net). This is the closest published, benchmarked answer to your exact unsolved problem: associating the same target seen by different drones — directly relevant to your dead SharedTargetInfo handoff logic.

"Drones Help Drones: A Collaborative Framework for Multi-Drone Object Trajectory Prediction" — NeurIPS 2024 (A*). Drones share trajectory predictions collaboratively rather than tracking in isolation.

Robotics / control-theoretic (IEEE T-RO — top robotics journal)
"Robust Multi-Robot Active Target Tracking Against Sensing and Communication Attacks" — Zhou & Kumar (Vijay Kumar's group, UPenn). IEEE Transactions on Robotics, 2023. This is the most relevant control-theoretic match to your project's actual gaps: a formal, provably-bounded algorithm (RATT) for multi-robot target tracking that stays robust when robots' sensing or communication links fail or are attacked — precisely the "what happens when a drone/sensor goes down" problem your code currently leaves as a TODO comment. It builds on the earlier RA-L paper "Resilient Active Target Tracking with Multiple Robots" (Zhou, Tzoumas, Pappas, Kumar) if you want the foundational version first.

If your goal is a related-work section: lead with the T-MM MDMT benchmark for the identity-association/handoff gap, and the T-RO robust tracking paper for the fault-tolerance gap — those two are the best-fit, top-venue matches to the two biggest structural holes we identified in your code (SharedTargetInfo and the missing sensor-crash recovery).

Sources:

Multi-Object Tracking Meets Moving UAV (CVPR 2022)
Robust Multi-Drone Multi-Target Tracking to Resolve Target Occlusion: A Benchmark (IEEE T-MM 2023)
Drones Help Drones: A Collaborative Framework for Multi-Drone Object Trajectory Prediction (NeurIPS 2024)
Robust Multi-Robot Active Target Tracking Against Sensing and Communication Attacks (IEEE T-RO 2023)
Resilient Active Target Tracking With Multiple Robots (arXiv version)