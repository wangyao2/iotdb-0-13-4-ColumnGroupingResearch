[English](./README.md) | [中文](./README_ZH.md)


# Repository Description
Welcome to the research code repositories for paper: Merging time series to multiple column groups for efficient storage schema

The research paper link can be referred to:... To be supplemented after paper accepted

# Description for the experimental system IoTDB

Apache IoTDB is a low-cost, high-performance native temporal database for the Internet of Things. It can solve various problems encountered by enterprises when building IoT big data platforms to manage time-series data, such as complex application scenarios, large data volumes, high sampling frequencies, high amount of unaligned data, long data processing time, diverse analysis requirements, and high storage and operation costs.

The main features of iotdb are as follows:

1 Flexible deployment methods: Support for one-click cloud deployment, out-of-the-box use after unzipping at the terminal, and seamless connection between terminal and cloud (data cloud synchronization tool).

2 Low hardware cost storage solution: Supports high compression ratio disk storage, no need to distinguish between historical and real-time databases, unified data management.

3 Hierarchical sensor organization and management: Supports modeling in the system according to the actual hierarchical relationship of devices to achieve alignment with the industrial sensor management structure, and supports directory viewing, search, and other capabilities for hierarchical structures.

4 High throughput data reading and writing: supports access to millions of devices, high-speed data reading and writing, out of unaligned/multi frequency acquisition, and other complex industrial reading and writing scenarios.

5 Rich time series query semantics: Supports a native computation engine for time series data, supports timestamp alignment during queries, provides nearly a hundred built-in aggregation and time series calculation functions, and supports time series feature analysis and AI capabilities.

6 Highly available distributed system: Supports HA distributed architecture, the system provides 7*24 hours uninterrupted real-time database services, the failure of a physical node or network fault will not affect the normal operation of the system; supports the addition, deletion, or overheating of physical nodes, the system will automatically perform load balancing of computing/storage resources; supports heterogeneous environments, servers of different types and different performance can form a cluster, and the system will automatically load balance according to the configuration of the physical machine.

7 Extremely low usage and operation threshold: supports SQL like language, provides multi language native secondary development interface, and has a complete tool system such as console.

8 Rich ecological environment docking: Supports docking with big data ecosystem components such as Hadoop, Spark, and supports equipment management and visualization tools such as Grafana, Thingsboard, DataEase

For the latest information about iotdb, please visit: [IoTDB](https://iotdb.apache.org/)。

# Brief introduction of research content
Columnar storage is employed in many time series databases.
The most common schema for managing time series is to store each independently.
An alternative schema is to merge them into a single group to share a common time column, reducing the redundancy in timestamps. However, additional data structures are needed to record null values.
Thus we aim to explore an intermediate schema by merging a collection of time series into multiple groups, where series within the same group share a time column, to find a more efficient storage schema. 

This issue was first proposed by scholar Fang. For the first proposal and research of the issue, please refer to the paper "grouping time series for efficient columnar storage"
Published in 2023, for details, please refer to the previous work https://dl.acm.org/doi/10.1145/3588703 .
Our goal is to explore other algorithms to solve this problem.

We establish a mathematical model for the grouping problem of time series, and solve this problem from the perspective of metaheuristic method and matching time series.
For details, please refer to the article "......supplement after paper accepted". Firstly, a pre-grouping algorithm based on DBSCAN is designed. According to the similarity of time series, the time series is divided into multiple groups to obtain the initial solutions.
We extract features from time series, and propose a method for measuring the similarity among time series using both features and timestamps overlap. Secondly, based on the pre-grouping results, genetic algorithm is further used to find the better solutions.
The crossover and mutation operators based on features are designed to guide the iteration and improve the efficiency. Finally, the method is implemented in Apache iotdb, and there are the codes.

# Code description
All our code is located in the java package "package org.apache.iotdb.db.engine.flush;".
For ReadME file of this code warehouse, we will rewrite it after the paper is accepted.

The class of all codes in the paper is "wangsgroupingengine.java".
Existing algorithms for experimental comparison can refer to the Java classes "flushgroupingengine.java" and "approximateflushgroupingengine.java"

In the Java class "wangsgroupingengine.java", we present the code of pre-grouping algorithm and feature-based genetic algorithm.
### (1) Trigger of grouping algorithm

The entry of grouping algorithm is the function “public static void grouping(AlignedTVList dataList, List<IMeasurementSchema> schemaList)”.
When the flush operation is triggered, this entry function is executed in the class "MemTableFlushTask".
### (2) Algorithm execution process

1 First, the algorithms do some standardized processing and record some information for the data in memory.

2 The pre-grouping algorithm is executed with the help of the function "wangsGroupingEngine.Pregrouping()", the logic of the pre-grouping algorithm can refer to the submitted manuscript.

3 After pre-grouping, the function "wangsGroupingEngine.mockbadpop (timeseriesnumber);" is executed to initialize the population of genetic algorithm.

4 Through a For loop, the individuals in the population is updated iteratively according to the process of genetic algorithm.

5 Calculate the fitness of all individuals in the population using function "wangsGroupingEngine.calculatescore();"

6 Update the population iteratively through three standard operators, and the three operators refer to the functions: "wangsGroupingEngine.evolve (datalist);" "wangsGroupingEngine.mutation();" "wangsGroupingEngine.mutation();"

Please refer to the submitted manuscript for more details.
### (3) Description of auxiliary class

1 class FeatureWang

    This class "FeatureWang" is used to record the features of each time series.
2 class Chromosome

    This class of "Chromosome" denotes a chromosome in GA. The attribute "intcombination" records the result of an encoding grouping pattern.

3 class CloumnGroupApprWang

    This class "CloumnGroupApprWang" is the computing unit in the pre-grouping algorithm.
### (4) Description of main functions

1 GettheNeighborofaPoint(): In the pre grouping algorithm, it calculates the neighbor points of a candidate time series.

2 AppromaxCalculateTheOverLapOrDistance(): In the pre grouping algorithm, it estimates the overlap between two time series by features

3 mockBadPop():  It initializes the population for genetic algorithm according to the results of pre-grouping.

4 computeProbability(),lcm(),NormSDist(): They are used to help calculate the distance between time series.

5 getParentChromosome2Accor(): It returns the parents in genetic algorithm using tournament strategies.