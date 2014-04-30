  这个项目主要目的是对Hive所支持的存储结构进行扩展。当前Hive所支持的存储结构是textfile,sequencefile,rcfile以及最新出来的ORC。他们各自的优势在这里就不详细说明了。下面谈谈要实现的存储结构FOSF的特点。

    FOSF结合了行和列混合存储的方式，行存储主要是用来保证一个元组的所有属性都存储在同一个block上，这样可以大大避免查询时期元组重构所带来的跨节点的网络IO开销，而采用列存储的话，一则是对于海量数据的OLAP这种查询而言可以避免查询不相关的列加载进入内存，二是由于数据列都是同一种数据类型，这使得列存储的压缩性能非常好。需要说明的是，先前曾尝试着采用列簇存储的方式，当初的动机是让查询相关的列放在一个列簇里面，这样的话可以减少查询时候的磁盘IO开销，但是这种方式就是灵活度太差，而且对于查询涉及到多个列簇的话等情况会加载额外的数据列进入内存，而且列簇的加载和重组也是一笔不小的开销，得不偿失。鉴于此，后来就放弃了列簇的想法。

     接下来，在行和列混合存储的基础上，引入了索引机制。就是为每一个列设立一个最小基本单元，基本单元可以byte size为单位，也可以记录个数为单位建立粗糙的索引，即记录这个列的最大值和最小值。请注意这是一种粗糙索引。这样的话在sql查询中会起到一定的过滤作用，可以避免查询涉及的列中无关的一部分数据加载进入内存。在当前的Hive执行流程中，如果需要利用此Index进行过滤的话，需要实现 filterPushDown机制。
   紧接着，鉴于当前所采用的压缩算法大多是LZO ZLIB等重量级的压缩编码方式，为了提高压缩性能进而节省存储空间以及提高查询性能，我们决定依据每一个数据列的属性和分布情况（连续重复，重复不连续，等差数列，相邻数值增量较小，甚至非重复个数较少等）采用最适合其的编码方式，具体的编码方式有deltabitPacking, DictionaryRedBlackTree,RunLengthEncoding,googleProtoBufferVLQ等编码，核心是考虑数据类型特点以及分布情况，关键技术是位编码，增量编码，字典编码，VLQ等,编码部分参考了twitter开源的产品 ，但是其算法是和其程序耦合在一起的，耦合度较高，需要开发者自己花功夫从其中提取，并在现有编码基础上组合出更加高效的编码方式。
   
   实现方式

   那这个存储结构是如何实现到Hive里面的呢？Hive提供了一个名为storagehandel的接口用于扩展其存储结构。用户只需要实现其相关接口，便可以让其支持你的存储结构。请注意，是支持基本功能，后来发现选择storageHandel来扩展存储结构有点坑。
   FOSF性能如何？
   在实现了上述功能之后，做了一个较为详细的性能测试，不管是从编码时间，解码时间，压缩比 以及sql查询时间来看都有较为明显的性能提升，github好像上不了PDF，就不方便和大家分享了。
   FOSF当前存在的问题以及后期规划：
   1.由于采用storageHandel接口来扩展FOSF，这就导致了很多问题，第一不支持小文件合并，这就直接导致我们在小文件较小的时候，map任务数过多而导致大大增加任务启动时间和查询时间。所以只能开发者实现小文件合并，其他 还有诸多查询方式居然也不能支持比如count(1)，只能开发者自己改，表示头都大了。
   2.前面提到，存储结构中使用了记录一个基本粒度的MaxMin用于在查询时候过滤掉一些无关的数据，这种索引技术是很粗糙的，在后来的性能测试中发现效果不太理想（当然，排序情况下会好很多，但不符合实际情况）。一个方法就是减小简建立Index的基本粒度，这样的话虽然能过滤掉更多无关的数据，但是建立更多的Index会增大存储空间和查询时候的计算代价。所以索引技术这一块，在混合存储的基础上确实没想到更优化的方法，行存储的细粒度的索引机制好像不太适合这种场景。
   3.在实现压缩算法的过程中，借鉴了googleProtoBuffer里面的VLQ算法，但是大家都知道Protobuffer更大的价值还在于其对结构化数据进行序列化存储的高效和简单，除了其紧凑的编码算法外，希望可以把protobuffer的序列化方式用在现有的FOSF存储结构上面。
   4.当前为每一个列选择一个编码算法是在创建表的时候定义的，问题是如果用户不知道表的数据列的分布情况，那么为其选择编码方式就是个问题。当前解决方案是为其使用一般情况下性能较好的增量位编码。后期规划是希望在数据进行加载的时候统计数据的分布情况动态的选择最适编码压缩方式。
  
   最后，一个人做这个东西表示压力有点大，又快找工作了，还有很多其他事情要所，最近又开始了一个新的任务--hadoop生态系统自动化部署以及性能监控。。。精力实在是有限。。。。
