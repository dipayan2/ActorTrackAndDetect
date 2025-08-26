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

    # Motion parameters
    ax = llx * 0.5 * (cx[0] + 1) * random.choice([-1, 1])  # x linear movement
    ay = lly * 0.5 * (cy[0] + 1) * random.choice([-1, 1])  # y linear movement

    bx = (cx[2] + 1) * 7
    fx = cx[3] / bx  # x frequency, x sin amplitude
    by = (cy[2] + 1) * 7
    fy = cy[3] / by  # y frequency, y sin amplitude

    if lci == 1: 
        fy = fx  # forcing the motion to be elliptical

    u = 12
    v = 0
    mx = v * N / u + ((u - 2 * v) / u) * N * cx[1] - (ax * t0 + bx * np.cos(fx * t0))
    my = v * N / u + ((u - 2 * v) / u) * N * cy[1] - (ay * t0 + by * np.sin(fy * t0))

    # Generate target positions for each time frame
    for t in range(T):
        xp = mx + (ax * t + bx * np.cos(fx * t))  # target x location at time t
        yp = my + (ay * t + by * np.sin(fy * t))  # target y location at time t

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

def main():
    """Main function to generate and export simulation data."""
    
    print("=== Target Tracking Simulation Generator ===\n")
    
    # Generate simulation
    simulation = generate_complete_simulation(N=100, T=2000, K=100, sig=0.1)
    
    print("\n=== Exporting Data for Scala Sensors ===\n")
    
    # Export target mask data (most useful for sensors)
    export_simulation_to_csv(simulation, "simulation_data", "W")
    
    # Export target coordinates (more efficient alternative)
    export_target_coordinates(simulation, "target_coordinates", "W", threshold=0.05)
    
    # Optionally export other data types
    print("\nOptional: Export other data types? (y/n)")
    export_others = input().lower().strip()
    
    if export_others == 'y':
        export_simulation_to_csv(simulation, "simulation_targets_background", "X")
        export_simulation_to_csv(simulation, "simulation_noisy", "Y")
        print("Additional data types exported!")
    
    print("\n=== Export Complete ===")
    print("\nTo use with your Scala sensors:")
    print('1. Update sensor constructor: Sensor(id, drone, minX, maxX, minY, maxY, "simulation_data")')
    print('2. Make sure the simulation_data directory is accessible to your Scala application')
    print('3. Each sensor will automatically filter for its monitoring area')

if __name__ == "__main__":
    main()