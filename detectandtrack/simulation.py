#!/usr/bin/env python3
"""
Complete Simulation Generator and Exporter for SNDSensor Integration

This script creates the full target tracking simulation and exports it
in formats that the Scala SNDSensor can read.
"""

import numpy as np
import random
import os
from pathlib import Path
import json

def add_target(X, W, N, T, llx, lly, lsx, lsy, lci, lts):
    """
    Add a single target with specified motion parameters to the simulation.
    
    Args:
        X: background tensor [T x N x N] 
        W: zeros tensor [T x N x N]
        N: frame dimension   
        T: number of frames
        llx: boolean for linear x motion
        lly: boolean for linear y motion
        lsx: boolean for sinusoidal x motion [NOT USED CURRENTLY]
        lsy: boolean for sinusoidal y motion [NOT USED CURRENTLY]
        lci: boolean for elliptical motion (fx = fy)
        lts: boolean for target shape (square vs gaussian)
    
    Returns:
        Zb: background + targets output variable [T x N x N]
        Zt: targets output variable (target mask) [T x N x N]
    """
    
    # Used for generating random slopes, intercepts and sinusoidal frequency
    cx = np.random.uniform(0, 1, size=4)  # x random values   
    cy = np.random.uniform(0, 1, size=4)  # y random values

    Zb = np.array(np.zeros([T, N, N]))  # background + targets output variable
    Zt = np.array(np.zeros([T, N, N]))  # targets output variable (target mask)
    ca = np.random.uniform(0.2, 0.2)     # target amplitude (constant for now)
    
    cs = np.random.uniform(0.2, 0.5)     # target size
    
    xv = list(range(N))
    x, y = np.meshgrid(xv, xv)  # coordinate grids

    mmat = np.zeros([N, N]) - ca  # matrix of target amplitude
    
    # Pick random time to force target to be at some location in frame
    t0 = T * np.random.uniform(0, 1)

    # Motion parameters - constrained for velocity < 0.1
    max_velocity = 0.1
    
    # Linear velocity components (pixels per frame)
    ax = llx * max_velocity * 0.3 * (cx[0] + 1) * random.choice([-1, 1])  # reduced from 0.5
    ay = lly * max_velocity * 0.3 * (cy[0] + 1) * random.choice([-1, 1])  # reduced from 0.5

    # Sinusoidal motion parameters - reduced amplitude and frequency
    bx = max_velocity * 0.4 * (cx[2] + 1)  # reduced amplitude
    fx = cx[3] / 100  # reduced frequency (was cx[3]/bx with larger bx)
    by = max_velocity * 0.4 * (cy[2] + 1)  # reduced amplitude  
    fy = cy[3] / 100  # reduced frequency

    if lci == 1: 
        fy = fx  # forcing the motion to be elliptical

    u = 12
    v = 0
    mx = v * N / u + ((u - 2 * v) / u) * N * cx[1] - (ax * t0 + bx * np.cos(fx * t0))
    my = v * N / u + ((u - 2 * v) / u) * N * cy[1] - (ay * t0 + by * np.sin(fy * t0))

    # Generate target positions for each time frame
    positions = []
    velocities = []
    
    for t in range(T):
        xp = mx + (ax * t + bx * np.cos(fx * t))  # target x location at time t
        yp = my + (ay * t + by * np.sin(fy * t))  # target y location at time t
        
        positions.append((xp, yp))
        
        # Calculate instantaneous velocity for verification
        if t > 0:
            vx = positions[t][0] - positions[t-1][0]  # velocity in x
            vy = positions[t][1] - positions[t-1][1]  # velocity in y
            speed = np.sqrt(vx**2 + vy**2)
            velocities.append(speed)
        
        if lts == 1:  # gaussian target
            mm1 = np.exp(-cs * (x - xp)**2 - cs * (y - yp)**2)
            mm1[mm1 < 0.1] = 0
            mm2 = 1 - mm1
            Zb[t] = mm2 * X[t] + mm1 * mmat
            Zt[t] = mm2 * W[t] + mm1 * mmat 
        else:  # square target
            xpr = np.round(xp)
            ypr = np.round(yp)
            mm1 = np.zeros([N, N])
            mm1[(x >= xpr) & (x <= xpr + cs) & (y >= ypr) & (y <= ypr + cs)] = 1
            mm2 = 1 - mm1
            Zb[t] = mm2 * X[t] + mm1 * mmat
            Zt[t] = mm2 * W[t] + mm1 * mmat 
    
    # Verify velocity constraint
    if velocities:
        max_velocity_actual = max(velocities)
        avg_velocity = np.mean(velocities)
        if max_velocity_actual > 0.1:
            print(f"Warning: Target exceeded velocity limit! Max: {max_velocity_actual:.3f}, Avg: {avg_velocity:.3f}")
        else:
            print(f"Target velocity OK - Max: {max_velocity_actual:.3f}, Avg: {avg_velocity:.3f}")
            
    return Zb, Zt

def add_noise(Z, W, lno, cn):
    """
    Add noise to the simulation.
    
    Args:
        Z: input tensor
        W: target mask tensor  
        lno: noise type (0=salt&pepper, 1=uniform, 2=normal)
        cn: noise amplitude
    
    Returns:
        Y: noisy data
        U: noisy target mask
    """
    
    if lno == 0:  # salt and pepper
        Y = np.copy(Z)
        U = np.copy(W)
        d1, d2, d3 = np.shape(Z)
        s = np.random.rand(d1, d2, d3) < cn
        p = np.random.rand(d1, d2, d3) < cn
        Y[s] = 0
        Y[p] = 1
        U[s] = 0
        U[p] = 1
    elif lno == 1:  # uniform
        N = np.random.uniform(0, cn, size=np.shape(Z))
        Y = Z + N
        U = W + N
    elif lno == 2:  # normal
        N = np.random.normal(0, cn, size=np.shape(Z))
        Y = Z + N
        U = W + N

    return Y, U

def generate_complete_simulation(N=200, T=200, K=15, sig=0.1):
    """
    Generate a complete simulation with multiple moving targets.
    
    Args:
        N: frame size (200x200 grid)
        T: number of time frames  
        K: number of targets
        sig: noise amplitude
        
    Returns:
        Dictionary containing all simulation arrays
    """
    
    print(f"Generating simulation: {N}x{N} grid, {T} frames, {K} targets")
    
    # Initialize arrays
    X0 = np.zeros([N, N])  # background
    W0 = np.zeros([N, N])  # zeros for target mask

    # Replicate for all time frames
    W = np.tile(W0, [T, 1, 1])  # target video
    X = np.tile(X0, [T, 1, 1])  # target + background video
    B = np.tile(X0, [T, 1, 1])  # background video

    # Generate targets with random motion patterns
    for k in range(K):
        print(f"  Generating target {k+1}/{K}")
        
        # Ensure some motion (not all motion booleans turned off)
        check = 0 
        while check == 0:
            v = np.random.randint(0, 2, size=6)
            check = np.sum(v[0:3])

        X, W = add_target(X, W, N, T, v[0], v[1], v[2], v[3], v[4], v[5])

    # Add noise
    print("  Adding noise...")
    Y, V = add_noise(X, W, 2, sig)  # Using normal noise

    # Return all arrays
    simulation_data = {
        'Y': Y,  # target + background + noise
        'V': V,  # target + noise  
        'X': X,  # target + background
        'W': W,  # target mask only
        'B': B,  # background only
        'metadata': {
            'N': N,
            'T': T, 
            'K': K,
            'noise_level': sig,
            'description': {
                'Y': 'targets + background + noise',
                'V': 'targets + noise',
                'X': 'targets + background',
                'W': 'target mask only',
                'B': 'background only'
            }
        }
    }
    
    print("Simulation generation complete!")
    return simulation_data

def export_simulation_to_csv(simulation_data, output_dir="simulation_data", data_type="W"):
    """
    Export simulation data to CSV files for Scala sensor consumption.
    
    Args:
        simulation_data: dict containing simulation arrays
        output_dir: directory to save CSV files
        data_type: which array to export ('W', 'X', 'Y', 'V', or 'B')
    """
    
    # Create output directory
    Path(output_dir).mkdir(exist_ok=True)
    
    # Get the specified data array
    data_array = simulation_data[data_type]
    metadata = simulation_data['metadata']
    
    T, N, _ = data_array.shape
    
    print(f"Exporting {data_type} data to CSV...")
    print(f"  Data type: {metadata['description'][data_type]}")
    print(f"  Frames: {T}, Grid size: {N}x{N}")
    
    # Export each frame as CSV
    for frame_idx in range(T):
        filename = f"{output_dir}/frame_{frame_idx}.csv"
        np.savetxt(filename, data_array[frame_idx], delimiter=',', fmt='%.6f')
        
        if frame_idx % 50 == 0:
            print(f"  Exported frame {frame_idx}/{T}")
    
    # Export metadata
    metadata_file = f"{output_dir}/metadata.json"
    export_metadata = {
        'simulation_type': data_type,
        'description': metadata['description'][data_type],
        'total_frames': T,
        'grid_size': N,
        'num_targets': metadata['K'],
        'noise_level': metadata['noise_level'],
        'data_range_min': float(np.min(data_array)),
        'data_range_max': float(np.max(data_array)),
        'frame_format': 'csv'
    }
    
    with open(metadata_file, 'w') as f:
        json.dump(export_metadata, f, indent=2)
    
    print(f"Export complete! Files saved to {output_dir}/")
    print(f"Metadata saved to {metadata_file}")

def export_target_coordinates(simulation_data, output_dir="target_coordinates", 
                             data_type="W", threshold=0.05):
    """
    Export only target coordinates (more efficient for sparse data).
    
    Args:
        simulation_data: dict containing simulation arrays
        output_dir: directory to save coordinate files
        data_type: which array to process 
        threshold: minimum intensity to consider as target
    """
    
    Path(output_dir).mkdir(exist_ok=True)
    
    data_array = simulation_data[data_type]
    T, N, _ = data_array.shape
    
    print(f"Exporting {data_type} target coordinates (threshold={threshold})...")
    
    target_counts = []
    
    for frame_idx in range(T):
        frame_data = data_array[frame_idx]
        
        # Find target locations above threshold
        target_rows, target_cols = np.where(np.abs(frame_data) > threshold)
        target_intensities = frame_data[target_rows, target_cols]
        
        # Create coordinate list: row, col, intensity
        if len(target_rows) > 0:
            target_list = np.column_stack((target_rows, target_cols, target_intensities))
            
            filename = f"{output_dir}/targets_frame_{frame_idx}.csv"
            np.savetxt(filename, target_list, delimiter=',', fmt='%d,%d,%.6f', 
                      header='row,col,intensity', comments='')
        else:
            # Create empty file for frames with no targets
            filename = f"{output_dir}/targets_frame_{frame_idx}.csv"
            with open(filename, 'w') as f:
                f.write('row,col,intensity\n')
        
        target_counts.append(len(target_rows))
        
        if frame_idx % 50 == 0:
            print(f"  Frame {frame_idx}: {len(target_rows)} targets")
    
    # Export summary statistics
    summary_file = f"{output_dir}/target_summary.json"
    summary_data = {
        'total_frames': T,
        'threshold': threshold,
        'avg_targets_per_frame': float(np.mean(target_counts)),
        'max_targets_per_frame': int(np.max(target_counts)),
        'min_targets_per_frame': int(np.min(target_counts)),
        'total_target_detections': int(np.sum(target_counts))
    }
    
    with open(summary_file, 'w') as f:
        json.dump(summary_data, f, indent=2)
    
    print(f"Target coordinates export complete!")
    print(f"Summary saved to {summary_file}")

def generate_target_trajectories(N=200, T=200, K=15):
    """
    Generate just the target trajectories without creating full frame arrays.
    
    Returns:
        Dictionary mapping frame_number -> list of (x, y, target_id) positions
    """
    
    print(f"Generating target trajectories: {K} targets over {T} frames")
    
    # Store all trajectories: frame -> [(x, y, target_id), ...]
    frame_targets = {frame: [] for frame in range(T)}
    
    for target_id in range(K):
        print(f"  Generating trajectory for target {target_id+1}/{K}")
        
        # Generate motion parameters for this target
        check = 0 
        while check == 0:
            v = np.random.randint(0, 2, size=6)
            check = np.sum(v[0:3])
        
        llx, lly, lsx, lsy, lci, lts = v
        
        # Random parameters for motion
        cx = np.random.uniform(0, 1, size=4)
        cy = np.random.uniform(0, 1, size=4)
        
        # Motion parameters - constrained for velocity < 0.1
        max_velocity = 0.1
        ax = llx * max_velocity * 0.3 * (cx[0] + 1) * random.choice([-1, 1])
        ay = lly * max_velocity * 0.3 * (cy[0] + 1) * random.choice([-1, 1])
        
        bx = max_velocity * 0.4 * (cx[2] + 1)
        fx = cx[3] / 100
        by = max_velocity * 0.4 * (cy[2] + 1)
        fy = cy[3] / 100
        
        if lci == 1: 
            fy = fx
        
        # Calculate center position
        t0 = T * np.random.uniform(0, 1)
        u = 12
        v_pos = 0
        mx = v_pos * N / u + ((u - 2 * v_pos) / u) * N * cx[1] - (ax * t0 + bx * np.cos(fx * t0))
        my = v_pos * N / u + ((u - 2 * v_pos) / u) * N * cy[1] - (ay * t0 + by * np.sin(fy * t0))
        
        # Generate positions for each frame
        velocities = []
        prev_pos = None
        
        for t in range(T):
            xp = mx + (ax * t + bx * np.cos(fx * t))
            yp = my + (ay * t + by * np.sin(fy * t))
            
            # Keep targets within bounds
            xp = max(0, min(N-1, xp))
            yp = max(0, min(N-1, yp))
            
            # Store position for this frame
            frame_targets[t].append((xp, yp, target_id))
            
            # Calculate velocity for verification
            if prev_pos is not None:
                vx = xp - prev_pos[0]
                vy = yp - prev_pos[1]
                speed = np.sqrt(vx**2 + vy**2)
                velocities.append(speed)
            
            prev_pos = (xp, yp)
        
        # Verify velocity constraint
        if velocities:
            max_vel = max(velocities)
            avg_vel = np.mean(velocities)
            if max_vel > 0.1:
                print(f"    Warning: Target {target_id} exceeded velocity! Max: {max_vel:.3f}")
            else:
                print(f"    Target {target_id} velocity OK - Max: {max_vel:.3f}, Avg: {avg_vel:.3f}")
    
    print("Target trajectory generation complete!")
    return frame_targets

def export_trajectory_data(frame_targets, output_dir="trajectory_data"):
    """
    Export trajectory data in simple format for Scala sensor.
    """
    Path(output_dir).mkdir(exist_ok=True)
    
    # Export as simple text files: frame_<t>.txt with "x,y,target_id" per line
    for frame_num, targets in frame_targets.items():
        filename = f"{output_dir}/frame_{frame_num}.txt"
        with open(filename, 'w') as f:
            f.write("x,y,target_id\n")  # header
            for x, y, target_id in targets:
                f.write(f"{x:.3f},{y:.3f},{target_id}\n")
    
    # Export metadata
    metadata = {
        'total_frames': len(frame_targets),
        'targets_per_frame': {f: len(targets) for f, targets in frame_targets.items()},
        'total_targets': len(set(target_id for targets in frame_targets.values() for _, _, target_id in targets)),
        'format': 'x,y,target_id per line'
    }
    
    import json
    with open(f"{output_dir}/metadata.json", 'w') as f:
        json.dump(metadata, f, indent=2)
    
    print(f"Trajectory data exported to {output_dir}/")
    print(f"Format: frame_<num>.txt with x,y,target_id per line")



def generate_target_trajectories_1000x1000_slow(N=1000, T=100, K=1000):
    """
    Generate target trajectories for 1000x1000 grid with ORIGINAL velocity values.
    
    Args:
        N: Grid size (1000)
        T: Number of frames (100)  
        K: Number of targets (1000)
    
    Returns:
        Dictionary mapping frame_number -> list of (x, y, target_id) positions
    """
    
    print(f"Generating 1000x1000 trajectories with ORIGINAL velocities: {K} targets over {T} frames")
    
    # Store all trajectories: frame -> [(x, y, target_id), ...]
    frame_targets = {frame: [] for frame in range(T)}
    
    for target_id in range(K):
        if target_id % 100 == 0:  # Progress logging
            print(f"  Generating trajectory for target {target_id+1}/{K}")
        
        # Generate motion parameters for this target
        check = 0 
        while check == 0:
            v = np.random.randint(0, 2, size=6)
            check = np.sum(v[0:3])
        
        llx, lly, lsx, lsy, lci, lts = v
        
        # Random parameters for motion
        cx = np.random.uniform(0, 1, size=4)
        cy = np.random.uniform(0, 1, size=4)
        
        # KEEP ORIGINAL: Same velocity as 100x100 system
        max_velocity = 0.1  # UNCHANGED from original
        ax = llx * max_velocity * 0.3 * (cx[0] + 1) * random.choice([-1, 1])  # ~±0.03 to ±0.06
        ay = lly * max_velocity * 0.3 * (cy[0] + 1) * random.choice([-1, 1])  # ~±0.03 to ±0.06
        
        bx = max_velocity * 0.4 * (cx[2] + 1)  # 0.04 to 0.08 amplitude
        fx = cx[3] / 100  # UNCHANGED frequency
        by = max_velocity * 0.4 * (cy[2] + 1)  # 0.04 to 0.08 amplitude
        fy = cy[3] / 100  # UNCHANGED frequency
        
        if lci == 1: 
            fy = fx
        
        # SCALED: Position parameters for 1000x1000 grid (but same motion)
        t0 = T * np.random.uniform(0, 1)
        u = 12
        v_pos = 0
        mx = v_pos * N / u + ((u - 2 * v_pos) / u) * N * cx[1] - (ax * t0 + bx * np.cos(fx * t0))
        my = v_pos * N / u + ((u - 2 * v_pos) / u) * N * cy[1] - (ay * t0 + by * np.sin(fy * t0))
        
        # Generate positions for each frame
        velocities = []
        prev_pos = None
        
        for t in range(T):
            xp = mx + (ax * t + bx * np.cos(fx * t))
            yp = my + (ay * t + by * np.sin(fy * t))
            
            # Keep targets within 1000x1000 bounds
            xp = max(0, min(N-1, xp))
            yp = max(0, min(N-1, yp))
            
            # Store position for this frame
            frame_targets[t].append((xp, yp, target_id))
            
            # Calculate velocity for verification
            if prev_pos is not None:
                vx = xp - prev_pos[0]
                vy = yp - prev_pos[1]
                speed = np.sqrt(vx**2 + vy**2)
                velocities.append(speed)
            
            prev_pos = (xp, yp)
        
        # Verify velocity constraint (should be same as original)
        if velocities and target_id % 200 == 0:  # Log every 200th target
            max_vel = max(velocities)
            avg_vel = np.mean(velocities)
            if max_vel > 0.1:  # Original threshold
                print(f"    Warning: Target {target_id} exceeded velocity! Max: {max_vel:.3f}")
            else:
                print(f"    Target {target_id} velocity OK - Max: {max_vel:.3f}, Avg: {avg_vel:.3f}")
    
    print("Target trajectory generation complete!")
    
    # Log statistics
    target_counts_per_frame = [len(targets) for targets in frame_targets.values()]
    print(f"Targets per frame - Min: {min(target_counts_per_frame)}, Max: {max(target_counts_per_frame)}, Avg: {np.mean(target_counts_per_frame):.1f}")
    
    return frame_targets

def export_trajectory_data_1000x1000_slow(frame_targets, output_dir="trajectory_data_1000x1000_slow"):
    """
    Export trajectory data for 1000x1000 grid with original velocities.
    """
    Path(output_dir).mkdir(exist_ok=True)
    
    # Export as simple text files: frame_<t>.txt with "x,y,target_id" per line
    for frame_num, targets in frame_targets.items():
        filename = f"{output_dir}/frame_{frame_num}.txt"
        with open(filename, 'w') as f:
            f.write("x,y,target_id\n")  # header
            for x, y, target_id in targets:
                f.write(f"{x:.3f},{y:.3f},{target_id}\n")
    
    # Export metadata
    metadata = {
        'grid_size': 1000,
        'total_frames': len(frame_targets),
        'targets_per_frame_stats': {
            'min': min(len(targets) for targets in frame_targets.values()),
            'max': max(len(targets) for targets in frame_targets.values()),
            'avg': np.mean([len(targets) for targets in frame_targets.values()])
        },
        'total_unique_targets': len(set(target_id for targets in frame_targets.values() for _, _, target_id in targets)),
        'motion_parameters': {
            'max_velocity': 0.1,  # UNCHANGED from original
            'velocity_range': '0.03 to 0.06 pixels/frame',
            'sinusoidal_amplitude': '0.04 to 0.08 pixels',
            'frequency_range': '0 to 0.01',
            'grid_cell_size': '2x2 units (same as original)',
            'sampling_interval': '100ms'
        },
        'drone_coverage': {
            'num_drones': 16,
            'drone_area': '300x300 each', 
            'overlap': '50 units',
            'expected_targets_per_drone': 'varies by density'
        },
        'format': 'x,y,target_id per line'
    }
    
    import json
    with open(f"{output_dir}/metadata.json", 'w') as f:
        json.dump(metadata, f, indent=2)
    
    print(f"SLOW velocity trajectory data exported to {output_dir}/")
    print(f"Format: frame_<num>.txt with x,y,target_id per line")
    print(f"Grid: 1000x1000, Frames: {len(frame_targets)}, Targets: {metadata['total_unique_targets']}")
    print(f"Velocities: UNCHANGED from original (max 0.1 pixels/frame)")


def main():
    """Main function to generate and export trajectory data."""
    
    print("=== Target Trajectory Generator ===\n")
    
    # Generate just trajectories (much faster than full simulation)
    # trajectories = generate_target_trajectories(N=100, T=100, K=50)
    
    # # Export in simple format
    # export_trajectory_data(trajectories, "trajectory_data")

        # Generate trajectories for 1000x1000 grid with ORIGINAL velocities
    trajectories = generate_target_trajectories_1000x1000_slow(N=1000, T=100, K=7000)
    
    # Export in simple format
    export_trajectory_data_1000x1000_slow(trajectories, "trajectory_data")
    
    print("\n=== Export Complete ===")
    print("To use with Scala sensors:")
    print('1. Update sensor constructor with: "trajectory_data"')
    print('2. Each file contains target positions for that frame')
    print('3. Sensors filter by their monitoring area')

if __name__ == "__main__":
    main()