#!/bin/bash

echo "Running EE290 FireSim Workload"
RDIR=$(pwd)
FSIM=$(pwd)/../../../sims/firesim/
cd $FSIM
source sourceme-f1-manager.sh
cd $RDIR
firesim launchrunfarm --runtimeconfigfile /home/centos/chipyard/generators/gemmini/software/firesim-configs/config_runtime_ee290.ini --hwdbconfigfile /home/centos/chipyard/generators/gemmini/software/firesim-configs/config_hwdb.ini 
firesim infrasetup --runtimeconfigfile /home/centos/chipyard/generators/gemmini/software/firesim-configs/config_runtime_ee290.ini --hwdbconfigfile /home/centos/chipyard/generators/gemmini/software/firesim-configs/config_hwdb.ini 
firesim runworkload --runtimeconfigfile /home/centos/chipyard/generators/gemmini/software/firesim-configs/config_runtime_ee290.ini --hwdbconfigfile /home/centos/chipyard/generators/gemmini/software/firesim-configs/config_hwdb.ini 
