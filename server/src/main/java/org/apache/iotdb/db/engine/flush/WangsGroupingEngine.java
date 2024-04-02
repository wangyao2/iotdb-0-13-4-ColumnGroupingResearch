package org.apache.iotdb.db.engine.flush;

import org.apache.iotdb.db.utils.datastructure.AlignedTVList;
import org.apache.iotdb.tsfile.utils.BitMap;
import org.apache.iotdb.tsfile.write.schema.IMeasurementSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.*;
import java.util.stream.Collectors;

import static org.apache.iotdb.db.rescon.PrimitiveArrayManager.ARRAY_SIZE;

/**
 * ClassName : WangsGroupingEngine
 * Package : flush
 * Description : 仿照刷写引擎还有近似刷写引擎，提出了我自己的一种刷写分组方法，使用预分组和遗传算法来求解
 */
public class WangsGroupingEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemTableFlushTask.class);
    private static List<Chromosome> pop = new ArrayList<Chromosome>();
    private static HashMap<Chromosome, ArrayList<ColumnGroupW>> theBigColumnGroups = new HashMap<>();
    private static Chromosome thebestChromosome;
    private static List<Chromosome> ListoftheHistorybestChromosome = new ArrayList<Chromosome>();

    //private static List<List<Integer>> ResultsOFthePreGroup = new ArrayList<>();//preGrouping方法的返回结果存储在这个里面
    private static List<CloumnGroupApprWang> ListoftheFeaturesWANG = new ArrayList<CloumnGroupApprWang>();//记录每一个序列的特征，按照下标顺序
    private static Map<Integer, List<Integer>> manyCluster = new HashMap<>();//存储预分组的结果

    private static int[] TheGolbalpreBestGroup;//在全局变量中存储一个预分组的最好结果
    int rowCount = 20;//DataList当中的rowCount
    int POP_SIZE = 10;//种群里个体的多少
    int CHROMOSOME_SIZE = 0;//
    int ITER_NUM = 10;// 迭代次数
    double MUTATION_RATE = 0.5;// 个体变异概率
    double MUTATION_RATE2 = 0.3;// 个体变异概率
    int MAX_MUTATION_NUM = 1;// 最大变异长度，只允许一个发生变异，我们再定义变异规则
    double bestScore = 9999999999999999999999999.99; // 当前这代种群中的最佳适应度
    double gloablbestScore = Double.MIN_VALUE;//定义全局最佳值
    double worstScore = Double.MAX_VALUE;// 当前这代种群中的最坏适应度
    double totalScore = 0;// 群体适应度得分（这里只限于当前群体）
    double averageScore = 0;// 平均群体适应度（需要注意，因为精度问题，我们要确保平均得分不得超过最好得分
    int popCount = 0;// 种群代数目统计

    public class ColumnGroupW {
        public ArrayList<Integer> columns;
        public double SpaceCost = 0;//当前组占用的空间
        public int maxGain = -1;
        public int maxGainPosition = -1;
        public long lastTimestampIdx;
        public long startTimestampIdx;
        public int timeSeriesLength; // number of exist timestamps, i.e., zeros in bitmap
        public long interval;
        public ArrayList<BitMap> bitmap;
        public int size; // total length of the bitmap
        public ArrayList<Integer> gains;

        public ColumnGroupW(
                ArrayList<Integer> columns, int maxGain, ArrayList<BitMap> bitmap, int size, int GroupNum) {
            this.columns = columns;
            this.maxGain = maxGain;
            this.bitmap = bitmap;
            this.size = size;
            //this.gains = new ArrayList<>(Collections.nCopies(GroupNum, -1));
            this.updateTimestampInfo();
        }

        public void updateTimestampInfo() {
            int onesCount = 0;
            this.startTimestampIdx = -1;
            this.lastTimestampIdx = -1;
            int bitmapSize = ARRAY_SIZE;//默认是32
            if (this.bitmap == null) {
            }

            for (int i = 0; i < this.bitmap.size(); i++) {
                if (this.bitmap.get(i) == null) {
                    if (this.startTimestampIdx == -1) {
                        this.startTimestampIdx = i * bitmapSize;
                    }
                    if (i * bitmapSize + bitmapSize - 1 < size) {
                        this.lastTimestampIdx = i * bitmapSize + bitmapSize - 1;
                    } else {
                        if (i * bitmapSize - 1 < size) {
                            this.lastTimestampIdx = size - 1;
                        }
                    }
                } else {//这一行代码的作用是，查查bitmap当中1的个数，这些都是null值，从而计算有效时间戳的个数
                    BitMap bm = this.bitmap.get(i);
                    for (int j = 0; j < bm.getSize() / Byte.SIZE; j++) {
                        int onesNumber = countOnesForByte(bm.getByteArray()[j]);
                        if (this.startTimestampIdx == -1 && onesNumber != 8) {
                            this.startTimestampIdx = i * bitmapSize + j * 8 + firstZero(bm.getByteArray()[j]);
                        }
                        if (onesNumber != 8) {
                            if (i * bitmapSize + j * 8 + lastZero(bm.getByteArray()[j]) < size) {
                                this.lastTimestampIdx = i * bitmapSize + j * 8 + lastZero(bm.getByteArray()[j]);
                            }
                        }
                        //onesCount += countOnesForByte(bm.getByteArray()[j]);
                        onesCount += onesNumber;
                    }
                }
            }
            this.timeSeriesLength = this.size - onesCount;
        }

        public ColumnGroupW mergeColumnGroup(ColumnGroupW g1, ColumnGroupW g2) {
            // merge bitmap
            ArrayList<BitMap> newBitmap = new ArrayList<>();
            for (int i = 0; i < g1.bitmap.size(); i++) {
                int bitmapLength = ARRAY_SIZE;
                byte[] bitmap = new byte[bitmapLength / Byte.SIZE + 1];
                for (int j = 0; j < bitmap.length; j++) {
                    byte g1byte = 0;
                    if (g1.bitmap.get(i) != null) {
                        g1byte = g1.bitmap.get(i).getByteArray()[j];
                    }
                    byte g2byte = 0;
                    if (g2.bitmap.get(i) != null) {
                        g2byte = g2.bitmap.get(i).getByteArray()[j];
                    }
                    bitmap[j] = (byte) (g1byte & g2byte);
                }
                newBitmap.add(new BitMap(ARRAY_SIZE, bitmap));
            }
            // merge columns
            ArrayList<Integer> newColumns = (ArrayList<Integer>) g1.columns.clone();
            newColumns.addAll(g2.columns);

            // update posIndex
            return new ColumnGroupW(newColumns, -1, newBitmap, g1.size, g1.gains.size() - 1);
        }

        public double getSpaceCost() {
            for (Integer column : columns) {

            }
            //SpaceCost = 0;
            return SpaceCost;
        }

    }

    private class CloumnGroupApprWang {
        public ArrayList<Integer> columns;
        public int maxGain = -1;
        public long lastTimestampIdx;
        public long startTimestampIdx;
        public int timeSeriesLength; // number of exist timestamps, i.e., zeros in bitmap
        public long interval;
        public ArrayList<BitMap> bitmap;
        public int size; // total length of the bitmap
        public ArrayList<FeatureWang> features;
        public ArrayList<Long> rangeMap;
        public boolean isVisited = false;//用于在DBSCAN当中记录当前点是否被访问过了
        public int belongedToAcluster = -2;//-2就是没有任何分组，-1就是单独一组，其他的就是分组编号

        public ArrayList<ArrayList<Integer>> rangeMapCols;

        public CloumnGroupApprWang(
                ArrayList<Integer> columns,
                int maxGain,
                ArrayList<BitMap> bitmap,
                int size,
                int GroupNum,
                AlignedTVList dataList) {
            this.columns = columns;
            this.maxGain = maxGain;
            this.bitmap = bitmap;
            this.size = size;
            this.updateTimestampInfo(dataList);//在这里面赋予时间序列的特征值
        }

        public void updateTimestampInfo(AlignedTVList dataList) {
            int onesCount = 0;
            this.startTimestampIdx = -1;
            this.lastTimestampIdx = -1;
            ArrayList<Long> timestamps = new ArrayList<>();
            int bitmapSize = ARRAY_SIZE;
            int intervalTimes = 1000;
            if (this.bitmap == null) {
                for (int i = 0; i < size; i++) {
                    timestamps.add(dataList.getTime(i) / intervalTimes);
                }
            } else {
                for (int i = 0; i < this.bitmap.size(); i++) {
                    if (this.bitmap.get(i) == null) {
                        if (this.startTimestampIdx == -1) {
                            this.startTimestampIdx = i * bitmapSize;
                        }
                        if (i * bitmapSize + bitmapSize - 1 < size) {
                            this.lastTimestampIdx = i * bitmapSize + bitmapSize - 1;
                            for (int k = i * bitmapSize; k <= i * bitmapSize + bitmapSize - 1; k++) {
                                timestamps.add(dataList.getTime(k) / intervalTimes);
                            }
                        } else {
                            if (i * bitmapSize - 1 < size) {
                                this.lastTimestampIdx = size - 1;
                                for (int k = i * bitmapSize; k <= size - 1; k++) {
                                    timestamps.add(dataList.getTime(k) / intervalTimes);
                                }
                            }
                        }
                    } else {
                        BitMap bm = this.bitmap.get(i);
                        for (int j = 0; j < bm.getSize() / Byte.SIZE; j++) {
                            int onesNumber = countOnesForByte(bm.getByteArray()[j]);
                            if (this.startTimestampIdx == -1 && onesNumber != 8) {
                                this.startTimestampIdx = i * bitmapSize + j * 8 + firstZero(bm.getByteArray()[j]);
                            }
                            if (onesNumber != 8) {
                                if (i * bitmapSize + j * 8 + lastZero(bm.getByteArray()[j]) < size) {
                                    this.lastTimestampIdx = i * bitmapSize + j * 8 + lastZero(bm.getByteArray()[j]);
                                    for (int k = i * bitmapSize + j * 8 + firstZero(bm.getByteArray()[j]);
                                         (k < size) && (k <= i * bitmapSize + j * 8 + lastZero(bm.getByteArray()[j]));
                                         k++) {
                                        if (!bm.isMarked(k - i * bitmapSize)) {
                                            timestamps.add(dataList.getTime(k) / intervalTimes);
                                        }
                                    }
                                }
                            }
                            onesCount += countOnesForByte(bm.getByteArray()[j]);
                        }
                    }
                }
            }
            int intervalGran = 1;
            long intervalSum = 1;
            int intervalCount = 1;
            int intervalMax = 1000;

            for (int i = 1; i < timestamps.size(); i++) {
                long interval_ = timestamps.get(i) - timestamps.get(i - 1);
                if (interval_ < intervalMax) {
                    intervalSum += interval_;
                    intervalCount++;
                }
            }
            long interval = intervalSum / intervalCount;
            interval = interval / intervalGran * intervalGran;

            // compute n
            this.timeSeriesLength = this.size - onesCount;
            // compute start
            long start = timestamps.get(0);
            double sigma = 0;
            double sigmaSum = 0;
            ArrayList<Double> sigmaList = new ArrayList<>();
            long offset = 0;
            for (int i = 0; i < timestamps.size(); i++) {
                sigma = Math.abs(timestamps.get(i) - start - i * interval - offset);
                if (sigma > 10 * interval) {
                    sigma = Math.abs(timestamps.get(i) - start - i * interval) % interval;
                    offset = Math.abs(timestamps.get(i) - start - i * interval) / interval * interval;
                }
                sigmaSum += sigma;
            }
            sigma = sigmaSum / timestamps.size();

            this.features = new ArrayList<>();
            this.features.add(new FeatureWang(interval, this.timeSeriesLength, start, sigma));

            this.rangeMap = new ArrayList<>();
            this.rangeMapCols = new ArrayList<>();
            rangeMap.add(start);//添加开始时间
            rangeMap.add(start + interval * (this.timeSeriesLength - 1));//添加终止时间

            ArrayList<Integer> overlapGroup = new ArrayList<>();
            overlapGroup.add(this.columns.get(0));
            rangeMapCols.add(overlapGroup);
        }

        public void setVisited(boolean visited) {
            isVisited = visited;
        }

        public void setBelongedToAcluster(int belongedToAcluster) {
            this.belongedToAcluster = belongedToAcluster;
        }
    }

    private class FeatureWang {
        public long epsilon;//时间间隔
        public int n;//序列有效长度
        public long start;//序列开始时间
        public double sigma;
        public long endtime;

        public FeatureWang(long epsilon_, int n_, long start_, double sigma_) {
            this.epsilon = epsilon_;
            this.n = n_;
            this.start = start_;
            this.sigma = sigma_;
            this.endtime = start_ + epsilon_ * (n_ - 1);
        }
    }

    private class Chromosome {
        int[] intcombination;//尝试用整数编码来做,例子[3,0,0,1,3,2,0,3,1,0]
        int[][] combination;//每一行代表一种组合方式，前几行不为0就代表分成了几组

        int numofpackage = 0;//矩阵的单维长度
        int numoftimeserie = 0;
        private double score;//记录本染色体对应的组合的适应度
        //public double Fitness_ScoreofTheGroups = 0;//记录本染色体对应的组合的适应度
        private double averagescoreAndPro = 0;
        ColumnGroupW thecloumnGroups;

        public Chromosome(int numoftimeseries) {
            //生成0-1编码方式的子类
            this.numoftimeserie = numoftimeseries;
            //定义编码属性
            combination = new int[numoftimeseries][numoftimeseries];
            boolean[] bitm = new boolean[numoftimeseries];//用来记录第几列是否有元素
            int numhasFilled = 0;//记录被赋值元素的个数
            int remainBitForFill = numoftimeseries;
            int randomRow;
            for (int i = 0; i < numoftimeseries; i++) {//给每一行的元素赋值
                //numOfFill += fillRandomRow(combination[i], numoftimeseries, numOfFill);
                if (numhasFilled == numoftimeseries) {//如果已经填充够了，就初始化完成
                    break;
                }
                for (int j = 0; j < numoftimeseries; j++) {//处理这一行当中的元素
                    if (i > 0 && bitm[j]) {//如果不是第一行，而且当前元素已经被赋值了，就跳过
                        //j--;
                        continue;
                    }
                    if (Math.random() < 0.4) {// 遍历这一行的每个元素，随机选择n个元素设置为1
                        combination[i][j] = 1;
                        bitm[j] = true;
                        numhasFilled++;
                        remainBitForFill = numoftimeseries - numhasFilled;
                        if (numhasFilled == numoftimeseries) {//如果已经填充够了，就初始化完成
                            break;//填这填这就够了
                        }
                    }
                }
            }
            //遍历bitmap，把还没有赋值的列给赋值
            for (int j = 0; j < numoftimeseries; j++) {
                if (!bitm[j]) {//如果当前列没有值
                    Random random = new Random();
                    randomRow = random.nextInt(numoftimeseries);
                    combination[randomRow][j] = 1;
                }
            }
            //把所有非0行移动到前面去，并且记录有多少分组numofpackage
            int[][] Tempcombination = new int[numoftimeseries][numoftimeseries];
            int countoftemp = 0;
            int boolflag = 0;
            for (int[] ints : combination) {
                boolflag = 0;
                for (int anInt : ints) {//判断当前行是否全0
                    boolflag = boolflag | anInt;
                }
                if (boolflag != 0) {
                    //当前行不全都是0，那么就把当前行放到新数组当中
                    Tempcombination[numofpackage++] = ints;
                }
            }
            combination = Tempcombination;
            System.out.println("初始化种群完成~");
        }

        public Chromosome(int[][] newCom) {
            //在0-1编码当中用于重新生成子代
            numoftimeserie = newCom.length;
            combination = newCom;
            for (int[] oneRow : newCom) {//遍历每一行
                for (int i : oneRow) {//遍历每一个元素
                    if (i == 1) {
                        numofpackage++;
                        break;
                    }
                }
            }
        }

        public Chromosome(int[][] newCom, boolean isNeedReRange) {
            int[][] Tempcombination = new int[numoftimeserie][numoftimeserie];
            int boolflag = 0;
            numofpackage = 0;//然后重新计数，这个染色体对应了多少个包裹
            for (int[] ints : newCom) {
                boolflag = 0;
                for (int anInt : ints) {//判断当前行是否全0
                    boolflag = boolflag | anInt;
                }
                if (boolflag != 0) {
                    //当前行不全都是0，那么就把当前行放到新数组当中
                    Tempcombination[numofpackage++] = ints;
                }
            }
            combination = Tempcombination;
        }

        public Chromosome(int[] newCom) {
            //用于整数编码当中，生成子代个体
            numoftimeserie = newCom.length;
            int packageleng = 1;//记录背包的数量，背包的编号是从1开始编的
            for (int i : newCom) {//更新背包的数量
                if (i > packageleng) packageleng = i;
            }
            intcombination = newCom.clone();
            numofpackage = packageleng;
        }

        public Chromosome(int numoftimeseries, int theInitPackage) {
            //生成整数数据编码方式的子代，第二个形参用来指定初始化背包的数量
            this.numoftimeserie = numoftimeseries;
            Random random = new Random();
            numofpackage = theInitPackage;
            intcombination = new int[numoftimeseries];//每个时间序列都安排一个背包去存放
            for (int i = 0; i < numoftimeseries; i++) {
                //给每一个时间序列安排一个背包,数组下标就代表了第几号时间序列
                intcombination[i] = random.nextInt(theInitPackage) + 1;//加1的目的是为了从1号背包开始编码
            }
        }

        public ArrayList<ColumnGroupW> transformToClumnGroup(AlignedTVList dataList) {
            //把1个染色体，变成对应的组合方式，对于整数编码方式来说，直接生成子类个体
            int size = dataList.rowCount();
            HashMap<Integer, ArrayList<Integer>> packages = new HashMap<>();//Key是第几个背包，里面的value list是当前package有哪些元素
            int TheDiscreatPackage = 1001;
            for (int i = 0; i < numoftimeserie; i++) {
                //i说明了当前是第几条序列
                int noOFpackage = intcombination[i];//拿出来这个是在第几个编号当中
                if(noOFpackage == -1){//如果是离散组的话，那么就解析成单独的package
                    noOFpackage = TheDiscreatPackage++;
                }
                if (packages.containsKey(noOFpackage)) {
                    // 如果键存在，获取对应的值ArrayList<Integer>
                    ArrayList<Integer> value = packages.get(noOFpackage);//获取背包当中已存在的序列
                    // 将新元素添加到ArrayList<Integer>中
                    value.add(i);//这个背包又增加了新的序列
                } else {
                    // 如果键不存在，创建一个新的ArrayList<Integer>并添加新元素
                    ArrayList<Integer> newValue = new ArrayList<>();
                    newValue.add(i);
                    // 将键值对添加到HashMap中
                    packages.put(noOFpackage, newValue);//给某个背包当中，添加某一条序列
                }
            }
            //下面把hashmap变成对应的组结构
            ArrayList<ColumnGroupW> ChromosomeTransFeredColumnGroup = new ArrayList<>();

            ArrayList<Integer> onePackage;
            for (Map.Entry<Integer, ArrayList<Integer>> onepackage : packages.entrySet()) {
                //int NOPackage = onepackage.getKey();
                onePackage = onepackage.getValue();
                ArrayList<BitMap> Bitmap = new ArrayList<>();
                ArrayList<BitMap> lastbitmap = new ArrayList<>();
                ArrayList<BitMap> Currentbitmap = new ArrayList<>();
                for (Integer j : onePackage) {
                    if (Bitmap.size() == 0) {
                        List<List<BitMap>> bitMapsForDebug = dataList.getBitMaps();
                        List<BitMap> bitMaps4 = bitMapsForDebug.get(4);
                        List<BitMap> bitMaps1 = bitMapsForDebug.get(1);
                        lastbitmap.addAll(dataList.getBitMaps().get(j));
                        Bitmap.addAll(dataList.getBitMaps().get(j));//不能直接等于，不然就成为了指针赋值
                    } else {
                        Currentbitmap.addAll(dataList.getBitMaps().get(j));
                        for (int ii = 0; ii < Currentbitmap.size(); ii++) {//将两个bitmap当中的位重叠
                            byte[] tembitmap = new byte[ARRAY_SIZE / Byte.SIZE + 1];
                            if (ii >= lastbitmap.size()) {
                                lastbitmap.add(Currentbitmap.get(ii));
                            } else {
                                for (int jj = 0; jj < tembitmap.length; jj++) {
                                    byte g1byte = 0;
                                    if (lastbitmap.get(ii) != null) {
                                        g1byte = lastbitmap.get(ii).getByteArray()[jj];
                                    }
                                    byte g2byte = 0;
                                    if (Currentbitmap.get(ii) != null) {
                                        g2byte = Currentbitmap.get(ii).getByteArray()[jj];
                                    }
                                    tembitmap[jj] = (byte) (g1byte & g2byte);//做且操作，
                                }
                                lastbitmap.set(ii, new BitMap(ARRAY_SIZE, tembitmap));//传入bitmap方便计算并集的
                            }
                        }
                        Currentbitmap.clear();
                    }
                }
                Bitmap.clear();
                Bitmap.addAll(lastbitmap);
                ChromosomeTransFeredColumnGroup.add(new ColumnGroupW(onePackage, -1, Bitmap, size, 0));
            }
            //每一次for循环都是处理一次小背包
            return ChromosomeTransFeredColumnGroup;
        }

        public void mutation3() {
            //用于整数编码方式当中，用于变异染色体生成,找到了以前的普通变异算子，作为对照试验
            Random rand = new Random();
            boolean[] hadSelectForMutation = new boolean[numoftimeserie];//掩码数组
            for (int j = 0; j < numoftimeserie; j++) {
                if (Math.random() < 0.3) {//30%的概率选择这一列
                    hadSelectForMutation[j] = true;
                }
            }
            //选择好变异的位
            for (int j = 0; j < numoftimeserie; j++) {
                if (hadSelectForMutation[j]) {
                    intcombination[j] = rand.nextInt(numofpackage) + 1;//背包编号是从1开始
                }
            }
            ReRearrangeTheArray();
        }

        public void mutation2() {
            Random rand = new Random();
            int targetrow = rand.nextInt(numofpackage);//在非0行中选择一行
            if (targetrow != 0) {//如果不是第一行，就把这一行中所有的1放到合并到一行当中
                for (int j = 0; j < numoftimeserie; j++) {//遍历本行的所有元素
                    if (combination[targetrow][j] == 1) {
                        combination[targetrow][j] = 0;
                        combination[targetrow - 1][j] = 1;
                        //break;//移除了break，使得这一行当中所有一元素都有可能被移动
                    }
                }
            }
            //把所有非0行移动到前面去，并且记录有多少分组numofpackage
            int[][] Tempcombination = new int[numoftimeserie][numoftimeserie];
            int boolflag = 0;
            numofpackage = 0;//然后重新计数，这个染色体对应了多少个包裹
            for (int[] ints : combination) {
                boolflag = 0;
                for (int anInt : ints) {//判断当前行是否全0
                    boolflag = boolflag | anInt;
                }
                if (boolflag != 0) {
                    //当前行不全都是0，那么就把当前行放到新数组当中
                    Tempcombination[numofpackage++] = ints;
                }
            }
            combination = Tempcombination;
        }

        public void mutation() {
            Random random = new Random();
            //1、按照特征交叉分组，生成1个孩子
            //随机获取一个特征，
            int WhichFeature = random.nextInt(5);
            boolean[] SelectedTheIndexOfSameFeature = new boolean[numoftimeserie];//选取某种具有相似特征的列，记录具有相似特征的列是哪些
            int randomI = 0;
            switch (WhichFeature) {
                case 0://选取为长度n
                    randomI = random.nextInt(numoftimeserie);//随机选取某一列作为标准
                    //randomI = 1;
                    CloumnGroupApprWang oneclou = ListoftheFeaturesWANG.get(randomI);//随机选取某一列作为标准
                    for (CloumnGroupApprWang oneCloumntarget : ListoftheFeaturesWANG) {
                        if (oneCloumntarget.features.get(0).n > oneclou.features.get(0).n * 0.6 &&
                                oneCloumntarget.features.get(0).n < oneclou.features.get(0).n * 1.5
                        ) {
                            SelectedTheIndexOfSameFeature[oneCloumntarget.columns.get(0)] = true;//选中了那些特征相似的列
                        }
                    }
                    break;
                case 1://选取为esploin
                    randomI = random.nextInt(numoftimeserie);//随机选取某一列作为标准
                    CloumnGroupApprWang oneclou1 = ListoftheFeaturesWANG.get(randomI);//随机选取某一列作为标准
                    for (CloumnGroupApprWang oneCloumntarget : ListoftheFeaturesWANG) {
                        if (oneCloumntarget.features.get(0).epsilon > oneclou1.features.get(0).epsilon * 0.6 &&
                                oneCloumntarget.features.get(0).epsilon < oneclou1.features.get(0).epsilon * 1.5
                        ) {
                            SelectedTheIndexOfSameFeature[oneCloumntarget.columns.get(0)] = true;//选中了那些特征相似的列
                        }
                    }
                    break;
                case 2://选取为start
                    randomI = random.nextInt(numoftimeserie);//随机选取某一列作为标准
                    CloumnGroupApprWang oneclou22 = ListoftheFeaturesWANG.get(randomI);//随机选取某一列作为标准
                    for (CloumnGroupApprWang oneCloumntarget : ListoftheFeaturesWANG) {
                        if (oneCloumntarget.features.get(0).start > oneclou22.features.get(0).start * 0.6 &&
                                oneCloumntarget.features.get(0).start < oneclou22.features.get(0).start * 1.5
                        ) {
                            SelectedTheIndexOfSameFeature[oneCloumntarget.columns.get(0)] = true;//选中了那些特征相似的列
                        }
                    }
                    break;
                default://就先所有都用长度判断
                    randomI = random.nextInt(numoftimeserie);//随机选取某一列作为标准
                    //randomI = 1;
                    CloumnGroupApprWang oneclou2 = ListoftheFeaturesWANG.get(randomI);//随机选取某一列作为标准
                    for (CloumnGroupApprWang oneCloumntarget : ListoftheFeaturesWANG) {//对比目标，目标的长度跟选中的长度差不多
                        if (oneCloumntarget.features.get(0).n > oneclou2.features.get(0).n * 0.6 &&
                                oneCloumntarget.features.get(0).n < oneclou2.features.get(0).n * 1.5 &&
                                oneCloumntarget.features.get(0).epsilon > oneclou2.features.get(0).epsilon * 0.6 &&
                                oneCloumntarget.features.get(0).epsilon < oneclou2.features.get(0).epsilon * 1.5
                        ) {
                            //给一个120%的幅度。确保特征的相似性，现在的问题是，只有自己跟自己是一组的，其他的选不来？，而且最佳值的地方出现了问题
                            SelectedTheIndexOfSameFeature[oneCloumntarget.columns.get(0)] = true;//选中了那些特征相似的列
                        }
                    }
            }
            int whichPackage = random.nextInt(numofpackage + 1) + 1;//随机选择一个背包
            //把这些具有相似特征的列，跟其他的背包合并
            for (int j = 0; j < numoftimeserie; j++) {
                if (SelectedTheIndexOfSameFeature[j]) {
                    intcombination[j] = whichPackage;//所有相似特征的列，合并到另外一个背包当中，也有可能发生不变
                }
            }
            //重新整理数组,因为可能发生背包的数量改变
            ReRearrangeTheArray();
        }

        public void ReRearrangeTheArray() {
            //对变异后的个体做重新整理数组的操作
            numofpackage = 0;
            HashMap<Integer, ArrayList<Integer>> packages = new HashMap<>();
            for (int i = 0; i < numoftimeserie; i++) {
                //i说明了当前是第几条序列
                int noOFpackage = intcombination[i];//拿出来这个是在第几个编号当中
                if (packages.containsKey(noOFpackage)) {
                    // 如果键存在，获取对应的值ArrayList<Integer>
                    ArrayList<Integer> value = packages.get(noOFpackage);//获取背包当中已存在的序列
                    // 将新元素添加到ArrayList<Integer>中
                    value.add(i);//这个背包又增加了新的序列
                } else {
                    // 如果键不存在，创建一个新的ArrayList<Integer>并添加新元素
                    ArrayList<Integer> newValue = new ArrayList<>();
                    newValue.add(i);
                    // 将键值对添加到HashMap中
                    packages.put(noOFpackage, newValue);//给某个背包当中，添加某一条序列
                }
            }
            for (ArrayList<Integer> onePacks : packages.values()) {
                numofpackage++;
                for (Integer onePack : onePacks) {//onePack就对应了一个一个的下表
                    intcombination[onePack] = numofpackage;
                }
            }
        }

        public void printThecombinationandPackAge() {
            for (int[] ints : combination) {
                System.out.println(Arrays.toString(ints));
            }
            System.out.println("package" + numofpackage);
        }

        public int[][] getCombination() {
            return combination;
        }

        public int getNumofpackage() {
            return numofpackage;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public void setAverageScoreAndPro(double totalscore) {
            this.averagescoreAndPro = score / totalscore;
        }

        public double getScore() {
            return score;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < numoftimeserie; j++) {
                sb.append(intcombination[j]);
                sb.append(", ");
            }
            return sb.toString();
        }
    }

    public static void grouping(AlignedTVList dataList, List<IMeasurementSchema> schemaList) {
        WangsGroupingEngine wangsGroupingEngine = new WangsGroupingEngine();
        theBigColumnGroups.clear();
        ListoftheHistorybestChromosome.clear();
        thebestChromosome = null;
        pop = new ArrayList<Chromosome>();
        try {
            if (dataList.getBitMaps() == null) {
                return;
            }
            int timeSeriesNumber = dataList.getValues().size();//获取序列的数量，n条数
            int size = dataList.rowCount();//获取数据点的数量，m数据点的个数
            if (timeSeriesNumber == 0) {
                return;
            }
            if (timeSeriesNumber == 1) {
                return;
            }
            byte[] bytes = new byte[5];
            for (int i = 0; i < timeSeriesNumber; i++) {
                ArrayList<BitMap> bitmap = (ArrayList<BitMap>) dataList.getBitMaps().get(i);
                if (bitmap == null) {
                    bitmap = new ArrayList<>();
                    for (int j = 0; j < dataList.getBitMaps().size(); j++) {
                        if (dataList.getBitMaps().get(j) != null) {
                            List<BitMap> bitMaps = dataList.getBitMaps().get(j);
                            int size1ofAbitmap = bitMaps.get(0).getSize();
                            int sizeBitmap = dataList.getBitMaps().get(j).size();
                            for (int k = 0; k < sizeBitmap; k++) {
                                bitmap.add(new BitMap(size1ofAbitmap, bytes.clone()));
                            }
                            break;//处理完一个序列后，就break直接处理下一个
                        }
                    }
                    dataList.getBitMaps().set(i,bitmap);//全部添加0元素再设置回去
                }
            }
            // init posMap, maxGain, groupingResult
            //0、通过统计量特征计算，完成预分组
            long startTimeOFgroupingtime = System.currentTimeMillis();
            wangsGroupingEngine.preGrouping(dataList);//返回一个预分组的个数
            //1、初始化原始种群，并且按照染色体的形状，生成对应的列组模式
            wangsGroupingEngine.mockBadPop(timeSeriesNumber);//启用预分组结果
            //wangsGroupingEngine.mockBadPorWithoutPreGrouping(timeSeriesNumber);//不使用预分组结果，直接全部随机生成
            wangsGroupingEngine.generate2TheNEWGroupsFromAchromosome(dataList);
            for (int i = 0; i < wangsGroupingEngine.ITER_NUM; i++) {
                //2、计算适应度、计算种群中每一种组合的收益和代价
                wangsGroupingEngine.calculateScore();//计算每一种组合的收益，并且将收益保存到每一个染色体中
                //3、选择父亲和母亲，完成进化操作，在过程中记录
                wangsGroupingEngine.evolve(dataList);
                //4、变异操作
                wangsGroupingEngine.mutation(true);
                //wangsGroupingEngine.mutation2(true);
                //5、交叉变异操作之后生成新的种群，根据染色体的状态
                wangsGroupingEngine.generate2TheNEWGroupsFromAchromosome(dataList);
                System.out.println(thebestChromosome.toString());
            }
            long endTimeOFgroupingtime = System.currentTimeMillis();
            outputGroupingTime(startTimeOFgroupingtime, endTimeOFgroupingtime);
            System.out.println("All the GA has overed!the best Chromosome can be seen: ");
            System.out.println(thebestChromosome.toString());
            //outputWangsResult(TheGolbalpreBestGroup, schemaList);//写入于预分组结果
            outputWangsResult(thebestChromosome, schemaList);//写入全局最优结果
        } catch (Exception e) {
            e.printStackTrace();
            LOGGER.error(e.getMessage());
        }
    }

    private static void outputGroupingTime(long start, long end) {
        String outputFile = "./time_costsWangs.csv";
        String outputFile2 = "F:\\Workspcae\\IdeaWorkSpace\\IotDBMaster2\\iotdbColumnExpr\\src\\iotdb-server-and-cli\\iotdb-server-autoalignment\\sbin";
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFile, true)));
            double gapOfTime = end - start;//毫秒转化成为秒
            out.write("Flush costs " + gapOfTime + "s\n");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
  }
    private int preGrouping(AlignedTVList dataList) {
        ListoftheFeaturesWANG.clear();
        manyCluster.clear();
        int timeSeriesNumber = dataList.getValues().size();
        int size = dataList.rowCount();

        for (int i = 0; i < timeSeriesNumber; i++) {
            ArrayList<Integer> newGroup = new ArrayList<>();
            newGroup.add(i);
            ArrayList<BitMap> bitmap = (ArrayList<BitMap>) dataList.getBitMaps().get(i);
            if (bitmap == null) {
                bitmap = new ArrayList<>();
                for (int j = 0; j < dataList.getBitMaps().size(); j++) {
                    if (dataList.getBitMaps().get(j) != null) {
                        int sizeBitmap = dataList.getBitMaps().get(j).size();
                        for (int k = 0; k < sizeBitmap; k++) {
                            bitmap.add(null);
                        }
                    }
                }
            }
            //每一条时间序列都初始化成一个带有特征的序列
            ListoftheFeaturesWANG.add(new CloumnGroupApprWang(newGroup, -1, bitmap, size, timeSeriesNumber, dataList));
        }

        int clusterNum = 1;//从1开始编号，跟后面的int数组编码对应起来
        int minpoints = 2;//在DBSCAN算法当中，成为核心点的话，应包含的最小数量
        double gap = 10;//在DBSCAN算法当中，邻域半径gap
        Queue<Integer> FuzhuQueue = new LinkedList<>();
        ArrayList<Integer> outliersInTheList = new ArrayList<>();
        System.out.println("min-max is over");
        for (CloumnGroupApprWang onecloumnP : ListoftheFeaturesWANG) {//for循环一次就是创建一个cluster
            FuzhuQueue.clear();//辅助队列总是空的，因为只有空了之后，才退出while循环
            if (onecloumnP.isVisited) {//如果被访问过了，就直接判断下一个点
                continue;
            }
            FuzhuQueue.add(onecloumnP.columns.get(0));
            while (!FuzhuQueue.isEmpty()) {//队列中的记作currentPP
                int currentPP = FuzhuQueue.poll();//拿到当前是哪一个列，计算当前点的临近点有哪些
                if (ListoftheFeaturesWANG.get(currentPP).isVisited) {
                    continue;
                }
                ListoftheFeaturesWANG.get(currentPP).setVisited(true);//设置为被访问过了
                ArrayList<Integer> NeighborsOfCurrentPoint = GettheNeighborofaPoint(currentPP);//拿到了当前的邻居点
                if (NeighborsOfCurrentPoint.size() == 0) {
                    outliersInTheList.add(currentPP);
                    ListoftheFeaturesWANG.get(currentPP).setBelongedToAcluster(-1);//设置成-1代表离群点，自己跟自己一组
                } else if (NeighborsOfCurrentPoint.size() < minpoints) {
                    //邻居数量不足，所以，不是核心点的话，那么就不用再往外扩展了
                    ListoftheFeaturesWANG.get(currentPP).setBelongedToAcluster(clusterNum);
                    for (Integer integer : NeighborsOfCurrentPoint) {
                        if (ListoftheFeaturesWANG.get(integer).belongedToAcluster != -2) {
                            //ListoftheFeaturesWANG.get(currentPoint).setVisited(true);
                            ListoftheFeaturesWANG.get(integer).setBelongedToAcluster(clusterNum);
                        }
                    }
                } else if (NeighborsOfCurrentPoint.size() >= minpoints) {
                    ListoftheFeaturesWANG.get(currentPP).setBelongedToAcluster(clusterNum);
                    for (Integer integer : NeighborsOfCurrentPoint) {
                        if (ListoftheFeaturesWANG.get(integer).belongedToAcluster == -2) {
                            //ListoftheFeaturesWANG.get(currentPoint).setVisited(true);
                            ListoftheFeaturesWANG.get(integer).setBelongedToAcluster(clusterNum);
                        }
                    }
                    //因为当前点是核心点，所以还需要把邻居点放入到辅助队列中做下一步判断，这个地方看上去可以增加去重操作
                    FuzhuQueue.addAll(NeighborsOfCurrentPoint);
                    List<Integer> collect = FuzhuQueue.stream().distinct().collect(Collectors.toList());//去除重复元素无法去重
                    FuzhuQueue.clear();
                    FuzhuQueue.addAll(collect);
                }
            }
            clusterNum++;
            //manyCluster.put(count++,oneNewcluster);//key是簇的编号，从1开始编
        }
        for (CloumnGroupApprWang oneCloumn : ListoftheFeaturesWANG) {
            int belongedToAcluster = oneCloumn.belongedToAcluster;
            if (manyCluster.containsKey(belongedToAcluster)) {
                manyCluster.get(belongedToAcluster).add(oneCloumn.columns.get(0));//如果这个组已经存在了，那么就往里面添加这个元素
            } else {
                ArrayList<Integer> oneCluster = new ArrayList<>();
                oneCluster.add(oneCloumn.columns.get(0));
                manyCluster.put(belongedToAcluster, oneCluster);
            }
        }
        return clusterNum + outliersInTheList.size();
    }

    private ArrayList<Integer> GettheNeighborofaPoint(int currentpoint) {
        //计算当前点的临近点有哪些
        ArrayList<Integer> result = new ArrayList<>();
        double distanceorOverlap = 0;
        double distanceorOverlap0 = 0;
        CloumnGroupApprWang curentP = ListoftheFeaturesWANG.get(currentpoint);
        float gap = 0;
        for (CloumnGroupApprWang onecloumn : ListoftheFeaturesWANG) {
            Integer target = onecloumn.columns.get(0);
            if (currentpoint == target) continue;
            CloumnGroupApprWang targerP = ListoftheFeaturesWANG.get(target);

            gap = (2 * (targerP.features.get(0).n + curentP.features.get(0).n)) / 34;//计算两个列之间的间隙
            distanceorOverlap = AppromaxCalculateTheOverLapOrDistance(currentpoint, target);
            if (distanceorOverlap >= gap) {//如果重叠度足够高，那么就认为是临近点，放入到一个组当中
                result.add(target);
            }
        }
        return result;
    }

    private double calculateTheOverLapOrDistance(int currentpoint, int target) {
        //在聚类的过程中计算两个序列的距离，通过计算重叠度的方法
        CloumnGroupApprWang g1 = ListoftheFeaturesWANG.get(currentpoint);
        CloumnGroupApprWang g2 = ListoftheFeaturesWANG.get(target);
        ArrayList<BitMap> col1 = g1.bitmap;
        ArrayList<BitMap> col2 = g2.bitmap;

        int bitmapSize = ARRAY_SIZE;
        int overlaps = 0;
        for (int i = 0; i < col1.size(); i++) {
            byte[] byteCol1 = null;
            byte[] byteCol2 = null;
            if (col1.get(i) != null) {
                byteCol1 = col1.get(i).getByteArray();
            }
            if (col2.get(i) != null) {
                byteCol2 = col2.get(i).getByteArray();
            }
            overlaps += computeOverlapForByte(byteCol1, byteCol2, bitmapSize);
        }
        int size = g1.size;//所有的列组当中，看上去size都是相同的
        int repeatOverlap = bitmapSize - (size % bitmapSize);
        if (size % bitmapSize == 0) {
            repeatOverlap = 0;
        }
        return overlaps - repeatOverlap;
    }

    private int AppromaxCalculateTheOverLapOrDistance(int currentpoint, int target) {
        //近似估算重叠程度
        CloumnGroupApprWang g1 = ListoftheFeaturesWANG.get(currentpoint);
        CloumnGroupApprWang g2 = ListoftheFeaturesWANG.get(target);
        ArrayList<Double> overlapList = new ArrayList<>();
        for (int i = 0; i < g2.columns.size(); i++) {
            FeatureWang f2 = g2.features.get(i);
            long start2 = f2.start;
            long end2 = f2.start + f2.epsilon * (f2.n - 1);
            double overlapTmp = 0;

            int t = 0;

            while ((t < g1.rangeMap.size() - 1) && (g1.rangeMap.get(t) < start2)) {
                t += 1;
            }
            if (t == g1.rangeMap.size() - 1) {
                overlapList.add(0.0);
                continue;
            } else {
                if ((t > 0) && (g1.rangeMap.get(t - 1) < start2)) {
                    int n2Tmp = (int) ((start2 - g1.rangeMap.get(t - 1)) / f2.epsilon);
                    double prob = 1;
                    for (int col : g1.rangeMapCols.get(t - 1)) {
                        for (int k = 0; k < g1.columns.size(); k++) {
                            if (g1.columns.get(k) == col) {
                                FeatureWang f1 = g1.features.get(k);
                                prob *= (1 - computeProbability(f1, f2));
                                break;
                            }
                        }
                    }
                    prob -= 1;
                    overlapTmp += prob * n2Tmp;
                }
            }

            if (g1.rangeMap.get(t) >= start2) {
                int j = t;
                while ((j < g1.rangeMap.size() - 1) && (g1.rangeMap.get(j) < end2)) {
                    j += 1;
                    int n2Tmp;
                    if (g1.rangeMap.get(j) < end2) {
                        n2Tmp = (int) ((g1.rangeMap.get(j) - g1.rangeMap.get(j - 1)) / f2.epsilon);
                    } else {
                        n2Tmp = (int) ((end2 - g1.rangeMap.get(j - 1)) / f2.epsilon);
                    }
                    double prob = 1;
                    for (int col : g1.rangeMapCols.get(j - 1)) {
                        for (int k = 0; k < g1.columns.size(); k++) {
                            if (g1.columns.get(k) == col) {
                                FeatureWang f1 = g1.features.get(k);
                                prob *= (1 - computeProbability(f1, f2));
                                break;
                            }
                        }
                    }
                    prob = 1 - prob;
                    overlapTmp += prob * n2Tmp;
                }
            }
            overlapList.add(overlapTmp);
        }

        double sumOverlap = 0;
        for (double overlap : overlapList) {
            sumOverlap += overlap;
        }
        int sumN = 0;
        for (FeatureWang f : g2.features) {
            sumN += f.n;
        }
        int overlapEstimation = (int) ((sumOverlap / sumN) * g2.timeSeriesLength);
        return overlapEstimation;
    }

    private double computeProbability(FeatureWang f1, FeatureWang f2) {
        int lambda = 3;
        int tau = 10;
        double prob = 0;
        long lcmInterval = lcm(f1.epsilon, f2.epsilon);
        long scenarios;
        if (f1.epsilon > f2.epsilon) {
            scenarios = lcmInterval / f1.epsilon;
            for (int i = 0; i < scenarios; i++) {
                int delta =
                        ((int) (f1.epsilon - f2.epsilon) * i + (int) f1.start - (int) f2.start)
                                % (int) f2.epsilon;
                if (f1.sigma == 0 && f2.sigma == 0) {
                    if (delta == 0) {
                        prob += 1;
                    } else {
                        prob += 0;
                    }
                } else {
                    double z1 =
                            ((lambda * tau) - delta) / Math.sqrt(f1.sigma * f1.sigma + f2.sigma * f2.sigma);
                    double z2 =
                            (-(lambda * tau) - delta) / Math.sqrt(f1.sigma * f1.sigma + f2.sigma * f2.sigma);
                    prob += NormSDist(z1) - NormSDist(z2);
                }
            }
        } else {
            scenarios = lcmInterval / f2.epsilon;
            for (int i = 0; i < scenarios; i++) {
                int delta =
                        ((int) (f2.epsilon - f1.epsilon) * i + (int) f2.start - (int) f1.start)
                                % (int) f1.epsilon;
                if (f1.sigma == 0 && f2.sigma == 0) {
                    if (delta == 0) {
                        prob += 1;
                    } else {
                        prob += 0;
                    }
                } else {
                    double z1 =
                            ((lambda * tau) - delta) / Math.sqrt(f1.sigma * f1.sigma + f2.sigma * f2.sigma);
                    double z2 =
                            (-(lambda * tau) - delta) / Math.sqrt(f1.sigma * f1.sigma + f2.sigma * f2.sigma);
                    prob += NormSDist(z1) - NormSDist(z2);
                }
            }
        }
        prob /= scenarios;
        return prob;
    }

    private long lcm(long number1, long number2) {
        if (number1 == 0 || number2 == 0) {
            return 0;
        }
        long absNumber1 = Math.abs(number1);
        long absNumber2 = Math.abs(number2);
        long absHigherNumber = Math.max(absNumber1, absNumber2);
        long absLowerNumber = Math.min(absNumber1, absNumber2);
        long lcm = absHigherNumber;
        while (lcm % absLowerNumber != 0) {
            lcm += absHigherNumber;
        }
        return lcm;
    }

    private double NormSDist(double z) {
        // compute approximate normal distribution cdf F(z)
        if (z > 6) return 1;
        if (z < -6) return 0;
        double gamma = 0.231641900,
                a1 = 0.319381530,
                a2 = -0.356563782,
                a3 = 1.781477973,
                a4 = -1.821255978,
                a5 = 1.330274429;
        double x = Math.abs(z);
        double t = 1 / (1 + gamma * x);
        double n =
                1
                        - (1 / (Math.sqrt(2 * Math.PI)) * Math.exp(-z * z / 2))
                        * (a1 * t
                        + a2 * Math.pow(t, 2)
                        + a3 * Math.pow(t, 3)
                        + a4 * Math.pow(t, 4)
                        + a5 * Math.pow(t, 5));
        if (z < 0) return 1.0 - n;
        return n;
    }

    private void mockBadPop(int numoftimeseries) {
        System.out.println("初始化坏种群，预分组的最优解为：");
        //添加把预分组结果进行解析的代码，生成一些初始解
        int initpackage = manyCluster.size();//有多少个cluster，就初始划分成多收个背包
        int[] preBestGroup = new int[numoftimeseries];
        for (CloumnGroupApprWang onecloumn : ListoftheFeaturesWANG) {
            preBestGroup[onecloumn.columns.get(0)] = onecloumn.belongedToAcluster;
            //这个数组，左边是拿到第几列，右边是这个列属于哪个分组
        }
        TheGolbalpreBestGroup = preBestGroup.clone();
        System.out.println(Arrays.toString(TheGolbalpreBestGroup));
        System.out.println("后面是迭代过程中的最优解：");
        for (int i = 0; i < POP_SIZE / 2; i++) {//种群当中一半的个体全都放入最有个体，任其去发生变异
            pop.add(new Chromosome(preBestGroup));//传入较优的分组状态
        }
        for (int i = pop.size(); i < POP_SIZE; i++) {//种群当中剩下的元素就随机生成
            //往种群里添加一个一个的染色体（组合方式）
            Chromosome chromosome = new Chromosome(numoftimeseries, initpackage);
            pop.add(chromosome);
        }
    }

    private void mockBadPorWithoutPreGrouping(int numoftimeseries) {
        System.out.println("初始化坏种群，预分组的最优解为：");
        int initpackage = manyCluster.size();//有多少个cluster，就初始划分成多收个背包
        int[] preBestGroup = new int[numoftimeseries];
        for (CloumnGroupApprWang onecloumn : ListoftheFeaturesWANG) {
            preBestGroup[onecloumn.columns.get(0)] = onecloumn.belongedToAcluster;
            //这个数组，左边是拿到第几列，右边是这个列属于哪个分组
        }
        TheGolbalpreBestGroup = preBestGroup.clone();
        initpackage = TheGolbalpreBestGroup.length - 1;//不设置初始背包的数量
        System.out.println(Arrays.toString(TheGolbalpreBestGroup));

        for (int i = pop.size(); i < POP_SIZE; i++) {//种群里所有的个体全部随机生成
            //往种群里添加一个一个的染色体（组合方式），全都随机成成
            Chromosome chromosome = new Chromosome(numoftimeseries, initpackage);
            pop.add(chromosome);
        }
    }
    private void generateTheNEWGroupsFromAchromosome(AlignedTVList dataList) {
        //本质上是染色体解码操作，这个函数的作用是把pop群体所有的chromosome全都制作成对应的group，并存到一个hashmap中
        theBigColumnGroups.clear();
        int size = dataList.rowCount();//获取数据点的数量，m数据点的个数
        //int timeSeriesNumber = dataList.getValues().size();//获取序列的数量，n条数
        for (Chromosome chromosome : pop) {
            ArrayList<ColumnGroupW> columnGroups = new ArrayList<>();//初始化种群，一个染色体对应一个columnGroups
            int[][] combination = chromosome.getCombination();
            int numofpackage = chromosome.getNumofpackage();
            //遍历种群中的所有组合方式，拿到一种组合方式，按照这种模式生成columnGroups
            for (int i = 0; i < numofpackage; i++) {
                //遍历每一个package，看看里面是怎么组合的
                ArrayList<Integer> onePackage = new ArrayList<>();
                ArrayList<BitMap> newBitmap = new ArrayList<>();
                ArrayList<BitMap> lastbitmap = new ArrayList<>();
                ArrayList<BitMap> Currentbitmap = new ArrayList<>();
                for (int j = 0; j < chromosome.numoftimeserie; j++) {
                    //每一个组合里面，检查每一列的元素，这里面一定是有元素1的
                    if (combination[i][j] == 1) {
                        onePackage.add(j);
                        //下面的bitmap部分是复制原来的，我们在此处又增加了多个时间序列放在同一组的bitmap的处理逻辑
                        if (newBitmap.size() == 0) {//如果当前bitmap还是空，说明还是第一个，直接赋值
                            //List<BitMap> bitMaps = dataList.getBitMaps().get(j);
                            //如何把一个List克隆到另一个当中？原来的方法是lastbitmap = (ArrayList<BitMap>) dataList.getBitMaps().get(j);
                            lastbitmap.addAll(dataList.getBitMaps().get(j));
                            //这个地方是浅拷贝，要用clone的深拷贝
                            newBitmap.addAll(dataList.getBitMaps().get(j));//不能直接等于，不然就成为了指针赋值
                        } else {//如果当前bitmap已经有了，那么就做与操作，计算时间戳的个数
                            //进入到这里，说明bitmap当中肯定有值了
                            //newBitmap.clear();//目前又遇到了问题，如果两条序列的bitmap长度不一致的话，那么就会发生越界
                            Currentbitmap.addAll(dataList.getBitMaps().get(j));
                            for (int ii = 0; ii < Currentbitmap.size(); ii++) {//将两个bitmap当中的位重叠
                                byte[] tembitmap = new byte[ARRAY_SIZE / Byte.SIZE + 1];
                                if (ii >= lastbitmap.size()) {//也就是说如果当前bitmap比之前的还要长，那么就添加新的进去，这里的等号问题
                                    lastbitmap.add(Currentbitmap.get(ii));
                                } else {
                                    for (int jj = 0; jj < tembitmap.length; jj++) {
                                        byte g1byte = 0;
                                        if (lastbitmap.get(ii) != null) {
                                            g1byte = lastbitmap.get(ii).getByteArray()[jj];
                                        }
                                        byte g2byte = 0;
                                        if (Currentbitmap.get(ii) != null) {
                                            g2byte = Currentbitmap.get(ii).getByteArray()[jj];
                                        }
                                        tembitmap[jj] = (byte) (g1byte & g2byte);//做且操作，
                                    }
                                    lastbitmap.set(ii, new BitMap(ARRAY_SIZE, tembitmap));//传入bitmap方便计算并集的
                                }
                            }
                            Currentbitmap.clear();
                            newBitmap.clear();
                            newBitmap.addAll(lastbitmap);
                        }
                    }
                }
                columnGroups.add(new ColumnGroupW(onePackage, -1, newBitmap, size, 0));
            }
            theBigColumnGroups.put(chromosome, columnGroups);//定义到前面去了，现在是全局变量，记录每一个染色体对应的groups
        }
    }

    private void generate2TheNEWGroupsFromAchromosome(AlignedTVList dataList) {
        theBigColumnGroups.clear();
        for (Chromosome chromosome : pop) {//把每一个染色体做成对应的组合
            ArrayList<ColumnGroupW> columnGroupWS = chromosome.transformToClumnGroup(dataList);
            theBigColumnGroups.put(chromosome, columnGroupWS);
        }
    }

    private void calculateScore() {
        if (pop == null || pop.size() == 0) {//种群为空，就初始化一个新种群
            pop = new ArrayList<Chromosome>();
            //mockBadPop();
        }
        totalScore = 0;
        double chromosomeScore = 0;
        for (Chromosome chromosome : pop) {
            chromosomeScore = calculateChromosomeScore(chromosome);
            chromosome.setScore(chromosomeScore);
            totalScore += chromosomeScore;
            //bestScore = Math.max(bestScore, chromosomeScore);
            if (chromosomeScore < bestScore) {//bestScore最开始时候是99999999，分数越小，那么越好
                bestScore = chromosomeScore;
                //thebestChromosome = chromosome;//记录当前最牛的个体，记录当前最佳的染色体组合模式，这里必须用克隆模式才行，不然在变异阶段，就全都被变化了
                thebestChromosome = new Chromosome(chromosome.intcombination.clone());
            }
        }
        averageScore = totalScore / POP_SIZE;
        //给每一个染色体都赋予权重，方便选择父母个体，现在采用锦标赛方法，不需要再记录被选择的概率
        if (averageScore > bestScore) averageScore = bestScore;
    }

    private double calculateChromosomeScore(Chromosome chromosome) {
        //遍历每一个染色体对应的群租模式，计算当前群组模式的收益
        ArrayList<ColumnGroupW> columnGroupWS = theBigColumnGroups.get(chromosome);
        double costofTheGroups = 0;
        //计算当前组合方式所占用的空间大小
        for (ColumnGroupW columnGroup : columnGroupWS) {
            //columnGroup.SpaceCost
            ArrayList<Integer> columns = columnGroup.columns;//拿到这一个package当中有哪些列
            int size = columns.size();//看这个一个小package当中包含了几个列，对应Bitmap的宽度，时间序列长度对应bitmap的长度
            if (size == 1) {//也就是只有一个列
                //costofTheGroups += columnGroup.timeSeriesLength * Long.SIZE;
                costofTheGroups += columnGroup.timeSeriesLength * 4;
            } else {
                //costofTheGroups += columnGroup.timeSeriesLength * Long.SIZE + ARRAY_SIZE * columnGroup.timeSeriesLength * size;
                //costofTheGroups += columnGroup.timeSeriesLength * Long.SIZE + columnGroup.timeSeriesLength * size;
                costofTheGroups += columnGroup.timeSeriesLength * 4 + columnGroup.timeSeriesLength * size;
            }
        }
        return costofTheGroups;//因为是求最小值，站的空间越小适应度越大
    }

    private double calculateChromosomeScore(ArrayList<ColumnGroupW> columnGroupWS) {
        //计算当前一个染色体对应的分数
        double costofTheGroups = 0;
        //计算当前组合方式所占用的空间大小
        for (ColumnGroupW columnGroup : columnGroupWS) {
            //columnGroup.SpaceCost
            ArrayList<Integer> columns = columnGroup.columns;//拿到这一个package当中有哪些列
            int size = columns.size();//看这个一个小package当中包含了几个列，对应Bitmap的宽度，时间序列长度对应bitmap的长度
            if (size == 1) {//也就是只有一个列
                costofTheGroups += columnGroup.timeSeriesLength * Long.SIZE;
            } else {
                //costofTheGroups += columnGroup.timeSeriesLength * Long.SIZE + ARRAY_SIZE * columnGroup.timeSeriesLength * size;
                costofTheGroups += columnGroup.timeSeriesLength * Long.SIZE + columnGroup.timeSeriesLength * size;
            }
        }
        return costofTheGroups;//因为是求最小值，站的空间越小适应度越大
    }

    private void evolve(AlignedTVList tvList) {
        List<Chromosome> newPop = new ArrayList<>();
        newPop.add(new Chromosome(thebestChromosome.intcombination.clone()));//把最好的个体，克隆后在加入到群体当中，避免后续对这个内容更改
        newPop.add(new Chromosome(thebestChromosome.intcombination.clone()));//把最好的个体，克隆后在加入到群体当中，避免后续对这个内容更改
        while (newPop.size() < POP_SIZE) {//每次都尝试扩大种群
            Chromosome p1 = getParentChromosome2Accor();//在种群中选择高于平均水平的父亲和母亲
            Chromosome p2 = getParentChromosome2Accor();//在种群中选择高于平均水平的父亲和母亲
            if (p1 == null || p2 == null) continue;
            List<Chromosome> children = genetic2AccordingTointCom(p1, p2, tvList);
            //List<Chromosome> children = genetic2AccordingTointCom_WithNormalCrossver(p1, p2, tvList);
            // 用高于平均水平的父亲和母亲，来产生后代，补充代码，生成后代的代码
            newPop.addAll(children);
        }
        pop.clear();
        pop = newPop;
        //在这里要生成新的个体
        popCount++;
    }

    private Chromosome getParentChromosome() {
        Random random = new Random();
        int size = ListoftheHistorybestChromosome.size();
        if (pop == null || pop.size() == 0) return null;
        int iterCount = 0;
        while (true) {
            double slice = totalScore * Math.random();
            double sum = 0d;
            for (Chromosome chromosome : pop) {
                sum += chromosome.getScore();
                // 一定要保证个体适应度大于平均适应度
                if (Math.random() > 0.6) {//优先选择平局情况的，不行的话，就以一定概率把当前返回
                    return chromosome;
                }
                if (sum > slice && chromosome.getScore() > averageScore) {
                    return chromosome;
                }
            }
            iterCount++;
            // 防止迭代次数过高，迭代次数超过阈值，随机选20次，还没选出来就返回最优个体，返回种群最优个体
            if (iterCount > 20) {
                if (size != 0) {//随机返回一个历史最佳的作为parent
                    return ListoftheHistorybestChromosome.get(random.nextInt(size));
                } else {
                    return thebestChromosome;
                }
            }
        }
    }

    private Chromosome getParentChromosome2Accor() {
        //生成父亲个体
        Random random = new Random();
        Chromosome theWinner;
        theWinner = pop.get(random.nextInt(POP_SIZE));//先随便找一个winner
        //先随便找一个winner，然后开始竞赛
        List<Chromosome> theReagyCompationList = new ArrayList<Chromosome>();//参与锦标赛的个体
        for (int i = 0; i < 3; i++) {//从原始种群里随机选5个染色体
            theReagyCompationList.add(pop.get(random.nextInt(POP_SIZE)));
        }
        for (Chromosome chromosome : theReagyCompationList) {
            if (chromosome.score < theWinner.score) {//如果当前的比获胜者厉害，那么，分数越小，那么空间占用越小，就胜出
                theWinner = chromosome;
            }
        }
        return theWinner;
    }

    private List<Chromosome> genetic2AccordingTointCom_WithNormalCrossver(Chromosome parent1, Chromosome parent2,  AlignedTVList tvlist) {
        //尝试增加，在进行交叉操作后，如果检测到合并收益下降的话，那么就不做合并操作，直接新构造一个对象，返回去
           //根据整数编码方式进行genetic，制作后代
        List<Chromosome> children = new ArrayList<>();
        //ArrayList<ColumnGroupW> columnGroups1 = theBigColumnGroups.get(parent1);
        //ArrayList<ColumnGroupW> columnGroups2 = theBigColumnGroups.get(parent1);
        int[] combination1 = parent1.intcombination.clone();
        int[] combination2 = parent2.intcombination.clone();
        int timeseriesnum = combination1.length;//多少个序列
        int[] newCom = new int[timeseriesnum];
        boolean[] hadSelectFromcombination1 = new boolean[timeseriesnum];//掩码数组
        //随机交叉，生成第1个孩子
        //随机交叉，生成第1个孩子
        for (int j = 0; j < timeseriesnum; j++) {
            if (Math.random() < 0.5) {//50%的概率选择这一列
                hadSelectFromcombination1[j] = true;
            }
        }
        for (int j = 0; j < timeseriesnum; j++) {
            if (hadSelectFromcombination1[j]) {
                newCom[j] = combination1[j];
            } else {
                newCom[j] = combination2[j];
            }
        }

        //计算子代是否更好，然后以一定概率接收新的子代个体
        Chromosome newchromosome = new Chromosome(newCom);
        ArrayList<ColumnGroupW> columnGroupWS = newchromosome.transformToClumnGroup(tvlist);
        double chromosomeScore = calculateChromosomeScore(columnGroupWS);
        newchromosome.setScore(chromosomeScore);
        //现在是基于一定的概率接纳新的个体，或者后续可以探索提出贪心的策略，来更新生成子代
        if ((chromosomeScore <= parent1.score || chromosomeScore <= parent2.score)) {// && Math.random() > 0.2
            children.add(newchromosome);
        } else if (parent1.score < parent2.score) {
            children.add(parent1);
        } else if (parent2.score < parent1.score) {
            children.add(parent2);
        }else {//忘了加else了，如果没有满足随机条件的话，也得返回
            children.add(newchromosome);
        }
        //ArrayList<ColumnGroupW> columnGroupWS = parent1.transformToClumnGroup();
        //children.add(newchromosome);这地方保留一个就行
        return children;
    }

    private List<Chromosome> genetic2AccordingTointCom(Chromosome parent1, Chromosome parent2, AlignedTVList tvlist) {
        //根据整数编码方式进行genetic，制作后代
        List<Chromosome> children = new ArrayList<>();
        int[] combination1 = parent1.intcombination.clone();
        int[] combination2 = parent2.intcombination.clone();
        int timeseriesnum = combination1.length;//多少个序列

        int numoftimeserie = combination1.length;
        Random random = new Random();
        boolean[] SelectedTheIndexOfSameFeature = new boolean[numoftimeserie];//选取某种具有相似特征的列，记录具有相似特征的列是哪些

        List<Chromosome> theReagyCompationList = new ArrayList<Chromosome>();
        theReagyCompationList.add(parent1);
        theReagyCompationList.add(parent2);
        int WhichFeature = random.nextInt(5);
        //检索所有特征相似的那些列，按照15%的比例波动
        switch (WhichFeature) {
            case 0:
                int randomIi = random.nextInt(numoftimeserie);//随机选取某一列作为标准
                CloumnGroupApprWang oneclou1 = ListoftheFeaturesWANG.get(randomIi);//随机选取某一列作为标准
                for (CloumnGroupApprWang oneCloumntarget : ListoftheFeaturesWANG) {//对比目标，目标的长度跟选中的长度差不多
                    if (oneCloumntarget.features.get(0).epsilon > oneclou1.features.get(0).epsilon * 0.8 &&
                            oneCloumntarget.features.get(0).epsilon < oneclou1.features.get(0).epsilon * 1.2) {
                        SelectedTheIndexOfSameFeature[oneCloumntarget.columns.get(0)] = true;//选中了那些特征相似的列
                    }
                }
                break;
            case 1:
                int randomIii = random.nextInt(numoftimeserie);//随机选取某一列作为标准
                CloumnGroupApprWang oneclou11 = ListoftheFeaturesWANG.get(randomIii);//随机选取某一列作为标准
                for (CloumnGroupApprWang oneCloumntarget : ListoftheFeaturesWANG) {//对比目标，目标的长度跟选中的长度差不多
                    if (oneCloumntarget.features.get(0).n > oneclou11.features.get(0).n * 0.8 &&
                            oneCloumntarget.features.get(0).n < oneclou11.features.get(0).n * 1.2) {
                        SelectedTheIndexOfSameFeature[oneCloumntarget.columns.get(0)] = true;//选中了那些特征相似的列
                    }
                }
                break;
            case 2:
                int randomIiii = random.nextInt(numoftimeserie);//随机选取某一列作为标准
                CloumnGroupApprWang oneclou111 = ListoftheFeaturesWANG.get(randomIiii);//随机选取某一列作为标准
                for (CloumnGroupApprWang oneCloumntarget : ListoftheFeaturesWANG) {//对比目标，目标的长度跟选中的长度差不多
                    if (oneCloumntarget.features.get(0).start > oneclou111.features.get(0).start * 0.8 &&
                            oneCloumntarget.features.get(0).start < oneclou111.features.get(0).start * 1.2) {
                        SelectedTheIndexOfSameFeature[oneCloumntarget.columns.get(0)] = true;//选中了那些特征相似的列
                    }
                }
                break;
            default://选取为长度n,就先所有都用长度判断
                int randomI = random.nextInt(numoftimeserie);//随机选取某一列作为标准
                CloumnGroupApprWang oneclou = ListoftheFeaturesWANG.get(randomI);//随机选取某一列作为标准
                for (CloumnGroupApprWang oneCloumntarget : ListoftheFeaturesWANG) {//对比目标，目标的长度跟选中的长度差不多
                    if (oneCloumntarget.features.get(0).n > oneclou.features.get(0).n * 0.8 &&
                            oneCloumntarget.features.get(0).n < oneclou.features.get(0).n * 1.2 &&
                            oneCloumntarget.features.get(0).epsilon > oneclou.features.get(0).epsilon * 0.8 &&
                            oneCloumntarget.features.get(0).epsilon < oneclou.features.get(0).epsilon * 1.2
                    ) {
                        //给一个120%的幅度。确保特征的相似性，现在的问题是，只有自己跟自己是一组的，其他的选不来？，而且最佳值的地方出现了问题
                        SelectedTheIndexOfSameFeature[oneCloumntarget.columns.get(0)] = true;//选中了那些特征相似的列
                    }
                }
        }
        //第1个孩子，按照长度特征进行交叉
        int[] newCom2 = new int[timeseriesnum];
        for (int j = 0; j < timeseriesnum; j++) {
            if (SelectedTheIndexOfSameFeature[j]) {
                newCom2[j] = combination1[j];
            } else {
                newCom2[j] = combination2[j];
            }
        }
        theReagyCompationList.add(new Chromosome(newCom2));
        //第2个孩子，按照长度特征进行交叉
        int[] newCom3 = new int[timeseriesnum];
        for (int j = 0; j < timeseriesnum; j++) {
            if (SelectedTheIndexOfSameFeature[j]) {
                newCom3[j] = combination2[j];
            } else {
                newCom3[j] = combination1[j];
            }
        }
        theReagyCompationList.add(new Chromosome(newCom3));
        //第3个孩子，按照随机片段进行交叉
        int[] newCom = new int[timeseriesnum];
        boolean[] hadSelectFromcombination1 = new boolean[timeseriesnum];//掩码数组
        //2、随机交叉，生成第1个孩子
        int first = random.nextInt(numoftimeserie);
        int second = random.nextInt(numoftimeserie);
        if (first < second){
            for (int j = first; j < second; j++) {
                hadSelectFromcombination1[j] = true;
            }
        }else {
            for (int j = second; j < first; j++) {
                hadSelectFromcombination1[j] = true;
            }
        }
        for (int j = 0; j < timeseriesnum; j++) {
            if (hadSelectFromcombination1[j]) {
                newCom[j] = combination1[j];
            } else {
                newCom[j] = combination2[j];
            }
        }
        theReagyCompationList.add(new Chromosome(newCom));

        Chromosome theWinner = theReagyCompationList.get(0);
        for (Chromosome chromosome : theReagyCompationList) {
            if (chromosome.score < theWinner.score) {//如果当前的比获胜者厉害，那么，分数越小，那么空间占用越小，就胜出
                theWinner = chromosome;
            }
        }
        children.add(theWinner);
        return children;
    }

    private void mutation(boolean isMutation) {
        for (Chromosome chromosome : pop) {
            if (Math.random() < MUTATION_RATE) {
                //System.out.println("在" + popCount +"代发生了变异！");
                chromosome.mutation();
            }
        }
    }

    private void mutation2(boolean isMutation) {
        if (!isMutation) {
            return;
        }
        for (Chromosome chromosome : pop) {
            if (Math.random() < MUTATION_RATE2) {
                //System.out.println("在" + popCount +"代发生了变异！");
                chromosome.mutation3();
            }
        }
    }


    //下面的内容，全部都是工具类
    private static void outputWangsResult(
        //把最佳染色体的编码转换成
        Chromosome thebestChromosome, List<IMeasurementSchema> schemaList) {
        String outputFile = "./grouping_results_wangs.csv";
        BufferedWriter out = null;
        int[] thebestCombination = thebestChromosome.intcombination;
        int numoftimeserie = thebestCombination.length;
        HashMap<Integer, ArrayList<Integer>> packages = new HashMap<>();
        int DiscritPoint = -100;
        for (int i = 0; i < numoftimeserie; i++) {
            //i说明了当前是第几条序列
            int noOFpackage = thebestCombination[i];//拿出来这个是在第几个编号当中
            if (noOFpackage == -1){
                noOFpackage = --DiscritPoint;//如果是-1离群点的话， 那就单独放一个list里面
            }
            if (packages.containsKey(noOFpackage)) {
                // 如果键存在，获取对应的值ArrayList<Integer>
                ArrayList<Integer> value = packages.get(noOFpackage);//获取背包当中已存在的序列
                // 将新元素添加到ArrayList<Integer>中
                value.add(i);//这个背包又增加了新的序列
            } else {
                // 如果键不存在，创建一个新的ArrayList<Integer>并添加新元素
                ArrayList<Integer> newValue = new ArrayList<>();
                newValue.add(i);
                // 将键值对添加到HashMap中
                packages.put(noOFpackage, newValue);//给某个背包当中，添加某一条序列
            }
        }
        try {
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFile, true)));
            for (ArrayList<Integer> onePacks : packages.values()) {
//                int onePack = 0;
//                for (; onePack < onePacks.size() -1; onePack++) {
//                    measurement = schemaList.get(onePack).getMeasurementId();
//                    out.write(measurement + ",");
//                }
//                measurement = schemaList.get(onePack).getMeasurementId();
//                out.write(measurement);
                for (Integer onePack : onePacks) {//onePack就对应了一个一个的下表
                    String measurement = schemaList.get(onePack).getMeasurementId();
                    out.write(measurement + ",");
                }
                out.write("\r\n");
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void outputWangsResult(int[] thebestint, List<IMeasurementSchema> schemaList) {
        String outputFile = "./grouping_results_wangs.csv";
        BufferedWriter out = null;
        int[] thebestCombination = thebestint;
        int numoftimeserie = thebestCombination.length;
        HashMap<Integer, ArrayList<Integer>> packages = new HashMap<>();
        int DiscritPoint = -100;
        for (int i = 0; i < numoftimeserie; i++) {
            //i说明了当前是第几条序列
            int noOFpackage = thebestCombination[i];//拿出来这个是在第几个编号当中
            if (noOFpackage == -1){
                noOFpackage = --DiscritPoint;//如果是-1离群点的话， 那就单独放一个list里面
            }
            if (packages.containsKey(noOFpackage)) {
                // 如果键存在，获取对应的值ArrayList<Integer>
                ArrayList<Integer> value = packages.get(noOFpackage);//获取背包当中已存在的序列
                // 将新元素添加到ArrayList<Integer>中
                value.add(i);//这个背包又增加了新的序列
            } else {
                // 如果键不存在，创建一个新的ArrayList<Integer>并添加新元素
                ArrayList<Integer> newValue = new ArrayList<>();
                newValue.add(i);
                // 将键值对添加到HashMap中
                packages.put(noOFpackage, newValue);//给某个背包当中，添加某一条序列
            }
        }
        try {
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFile, true)));
            for (ArrayList<Integer> onePacks : packages.values()) {
                for (Integer onePack : onePacks) {//onePack就对应了一个一个的下表
                    String measurement = schemaList.get(onePack).getMeasurementId();
                    out.write(measurement + ",");
                }
                out.write("\r\n");
            }
            out.write("^======PreGrouping=====^");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    public static int computeOverlapForByte(byte[] col1, byte[] col2, int size) {
        if (col1 == null && col2 == null) {
            return size;
        }
        int onesCount = 0;
        if (col1 == null) {
            for (int i = 0; i < size / Byte.SIZE; i++) {
                onesCount += countOnesForByte(col2[i]);
            }
            return size - onesCount;
        }
        if (col2 == null) {
            for (int i = 0; i < size / Byte.SIZE; i++) {
                onesCount += countOnesForByte(col1[i]);
            }
            return size - onesCount;
        }
        for (int i = 0; i < size / Byte.SIZE; i++) {
            onesCount += countOnesForByte((byte) (col1[i] | col2[i]));
        }
        return size - onesCount;
    }


    /**
     * belows are some util functions*
     */
    public static int countOnesForByte(byte x) {
        return ((x >> 7) & 1)
                + ((x >> 6) & 1)
                + ((x >> 5) & 1)
                + ((x >> 4) & 1)
                + ((x >> 3) & 1)
                + ((x >> 2) & 1)
                + ((x >> 1) & 1)
                + (x & 1);
    }

    public static int firstZero(byte x) {
        for (int i = 0; i <= 7; i++) {
            if (((x >> i) & 1) == 0) {
                return i;
            }
        }
        return -1;
    }

    public static int lastZero(byte x) {
        for (int i = 7; i >= 0; i--) {
            if (((x >> i) & 1) == 0) {
                return i;
            }
        }
        return -1;
    }
}
