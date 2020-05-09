  #!/usr/bin/env bash

echo "*****************TEST RESULTS*************" > test_output.txt
echo "=========cifar_quant0_og========="
echo "=========cifar_quant0_og=========" >> test_output.txt
/root/ee290/cifar_quant0_og-linux >> test_output.txt
echo "=========cifar_quant1_og========="
echo "=========cifar_quant1_og=========" >> test_output.txt
/root/ee290/cifar_quant1_og-linux >> test_output.txt
echo "=========cifar_quant2_og========="
echo "=========cifar_quant2_og=========" >> test_output.txt
/root/ee290/cifar_quant2_og-linux >> test_output.txt
echo "=========cifar_quant3_og========="
echo "=========cifar_quant3_og=========" >> test_output.txt
/root/ee290/cifar_quant3_og-linux >> test_output.txt
echo "=========cifar_quant4_og========="
echo "=========cifar_quant4_og=========" >> test_output.txt
/root/ee290/cifar_quant4_og-linux >> test_output.txt
echo "=========cifar_quant0_rt========="
echo "=========cifar_quant0_rt=========" >> test_output.txt
/root/ee290/cifar_quant0_rt-linux >> test_output.txt
echo "=========cifar_quant1_rt========="
echo "=========cifar_quant1_rt=========" >> test_output.txt
/root/ee290/cifar_quant1_rt-linux >> test_output.txt
echo "=========cifar_quant2_rt========="
echo "=========cifar_quant2_rt=========" >> test_output.txt
/root/ee290/cifar_quant2_rt-linux >> test_output.txt
echo "=========cifar_quant3_rt========="
echo "=========cifar_quant3_rt=========" >> test_output.txt
/root/ee290/cifar_quant3_rt-linux >> test_output.txt
echo "=========cifar_quant4_rt========="
echo "=========cifar_quant4_rt=========" >> test_output.txt
/root/ee290/cifar_quant4_rt-linux >> test_output.txt
cat test_output.txt
poweroff -f