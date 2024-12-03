
[English](./README.md) | [中文](./README_ZH.md)


# 仓库说明
欢迎来到研究《Merging time series to multiple column groups for efficient storage schema》的代码仓库。

文章链接可以参考：……等待录用后补充

# 数据库简介
IoTDB (Internet of Things Database) 是一款时序数据库管理系统，可以为用户提供数据收集、存储和分析等服务。IoTDB由于其轻量级架构、高性能和高可用的特性，以及与 Hadoop 和 Spark 生态的无缝集成，满足了工业 IoT 领域中海量数据存储、高吞吐量数据写入和复杂数据查询分析的需求。

IoTDB的主要特点如下:
1. 灵活的部署策略。IoTDB为用户提供了一个在云平台或终端设备上的一键安装工具，以及一个连接云平台和终端上的数据的数据同步工具。
2. 硬件成本低。IoTDB可以达到很高的磁盘存储压缩比。
3. 高效的目录结构。IoTDB支持智能网络设备对复杂时间序列数据结构的高效组织，同类设备对时间序列数据的组织，海量复杂时间序列数据目录的模糊搜索策略。
4. 高吞吐量读写。IoTDB支持数以百万计的低功耗设备的强连接数据访问、高速数据读写，适用于上述智能网络设备和混合设备。
5. 丰富的查询语义。IoTDB支持跨设备和测量的时间序列数据的时间对齐、时间序列字段的计算(频域转换)和时间维度的丰富聚合函数支持。
6. 学习成本非常低。IoTDB支持类似sql的语言、JDBC标准API和易于使用的导入/导出工具。
7. 与先进的开放源码生态系统的无缝集成。IoTDB支持分析生态系统，如Hadoop、Spark和可视化工具(如Grafana)。

有关IoTDB的最新信息，请访问[IoTDB官方网站](https://iotdb.apache.org/)。

# 研究内容简介
时间序列数据库都采用列式存储。管理时间序列最常见的模式是独立地存储每个时间序列。
另一种模式是将它们合并到一个组中以共享一个公共时间列，从而减少时间戳中的冗余。
但是，这需要额外的数据结构来记录null值。
因此，我们的目标是通过将时间序列集合合并为多个组来探索一种中间模式，其中同一组中的序列共享一个时间列，以找到一种更高效的存储模式。
这个问题最早是由Fang等学者率先提出，问题的提出和研究可以参考论文《Grouping Time Series for Efficient Columnar Storage》
发表于2023年，详细可以参考 https://dl.acm.org/doi/10.1145/3588703 。
我们的目标则在于探索其他的算法来解决此问题。
我们为时间序列的分组问题建立了数学模型，并且以元启发式方法和序列匹配的角度出发解决此问题。详细可以参考文章《录用后补充》。首先，基于DBSCAN设计了一种预分组算法，根据时间序列的相似性将时间序列划分为多个组，得到初始解。
我们从时间序列中提取特征，并提出一种同时考虑特征和时间重叠的时间序列相似性量化方法。其次，在预分组结果的基础上，进一步利用遗传算法寻找最优解。
设计了基于特征的交叉和变异算子来指导迭代，提高效率。最后，在Apache IoTDB中实现了该方法。实验表明了该算法的优越性和列组模式的潜在价值。

# 代码说明
我们所有的代码位于java包“package org.apache.iotdb.db.engine.flush;”。
对于代码仓库的ReadME，我们将会在论文录用后重新编写。

论文中所有代码的类是“WangsGroupingEngine.java”。
作为实验对比的现有算法可以参考java类“FlushGroupingEngine.java”以及“ApproximateFlushGroupingEngine.java”.

在java类“WangsGroupingEngine.java”里面，我们呈现了预分组算法和遗传算法的代码。

### （1）分组算法的触发

分组算法的入口方法是“public static void grouping(AlignedTVList dataList, List<IMeasurementSchema> schemaList)”。
当触发Flush操作时，在MemTableFlushTask类中执行此入口函数。

### （2）算法执行流程

    1 首先，对内存中数据做部分标准化处理和简单的信息记录。
    2 执行预分组算法借助函数“wangsGroupingEngine.preGrouping()”，预分组算法的逻辑可参考提交的手稿（仓库内的说明将在录用后填充）。
    3 完成预分组后，执行函数“wangsGroupingEngine.mockBadPop(timeSeriesNumber);”初始化遗传算法的初代种群。
    4 通过一个for循环按照遗传算法的逻辑不断迭代更新种群内的解。
    5 计算种群中所有粒子的适应度“wangsGroupingEngine.calculateScore();”
    6 通过三种标准算子迭代更新种群，三种算子分别参考方法“wangsGroupingEngine.evolve(dataList);”“wangsGroupingEngine.mutation(true);”“wangsGroupingEngine.mutation(true);”
适应度的计算方法和算子的运行逻辑请参考提交的手稿。

### （3）辅助类的说明

1 class FeatureWang

    这个类FeatureWang负载记录每一条时间序列的特征。

2 class Chromosome

    这个类Chromosome是遗传算法中的染色体，其中的属性intcombination记录了一个分组模式编码后的结果

3 class CloumnGroupApprWang
    
    这个类CloumnGroupApprWang是预分组算法中参与计算的单元

### （4）主要函数说明

1 GettheNeighborofaPoint() 在预分组算法中，计算一个候选序列的邻居点有哪些

2 AppromaxCalculateTheOverLapOrDistance() 在预分组算法中，通过特征估算两条序列之间的重叠度

3 mockBadPop() 为遗传算法初始化种群，按照预分组的结果

4 computeProbability(),lcm(),NormSDist() 用于辅助计算序列之间的距离

5 getParentChromosome2Accor() 用于获得遗传算法中的父个体