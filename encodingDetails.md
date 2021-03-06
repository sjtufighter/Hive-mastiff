整型编码调研
----

1.整型编码(http://lemire.me/blog/archives/2012/03/06/how-fast-is-bit-packing/)
----
此部分主要分为 plain Encoding ，Dictionary Encoding，Delta Encoding，bit-packing Enconding  和 Run Length Encoding 基本编码。

1.1 plain Encoding：

最简单编码。即按照value出现的顺序依次进行存储，唯一需要注意的是，在存储值的时候采用的是little endian，对于像Int这样由多个字节组成的数值类型，其采用的是先存储低位字节，然后存储高位字节。原因是发现通过对Integer.reverseBytes(in.readInt())和 keep a member byte[4], wrapped by an IntBuffer with appropriate endianness set  and call IntBuffer.get()这两个方法的测试，小端存储比大端存储更快。

1.2 Dictionary Encoding：

字典编码主要由字典中的字典值以及存储value的字典项编号组成。比如：现在字典是5（1）,8（2）,10（3） 其字典值是5,8,10.对应的字典编号是1,2,3.现在需要存储数值5,8,5,10,8. 那么存储的顺序值就是其id号，即12132.

1.3 bit-packing Enconding：

举例来说明，现在需要存储java  int类型的0,1,2,3,4,5,6,7这8个值，在不做任何处理的情况下需要占用的大小是4*8即32个字节,32*8 个位。现在采用位编码来存储这些数据：存储规则是计算需要存储的值中的在最大值的实际占用的有效位。比如上述值中的最大值是7，其具体存储是00000000  00000000  00000000  00000111
则其有效位是111，我们只需要用三个bit就可以存储所有比此最大值小的值，所以采用如下的位编码进行存储，占用的存储空间是3*8=24 bit，大大节省了存储空间：
dec value: 0   1   2   3   4   5   6   7
bit value: 000 001 010 011 100 101 110 111
bit label: ABC DEF GHI JKL MNO PQR STU VWX
would be encoded like this where spaces mark byte boundaries (3 bytes):
bit value: 00000101 00111001 01110111
bit label: ABCDEFGH IJKLMNOP QRSTUVWX

1.4 Run Length Encoding：  

此编码对于连续多次出现的重复编码很有效，这里的RLE编码和一般意义上的RLE的主要区别是，其内部使用了前面提到的位编码来存储值和值出现的次序。比如111222（假设每个值用一个字节表示，则一共6个字节），即 1 3 2 3 。则存储是： 00000001   00000011  00000010  00000011  需要4个字节。如果采用位编码的话，则存储是：01 11 10 11 一共8个位即可以表达。

1.5 Delta  bit -packing  Encoding：

即增量编码和位编码混合编码。以7,5,3,1,2,3,4,5为例：先计算出两个相邻的值的增量，分别为 -2 -2 -2 1 1 1 1 ，在计算的同时保存最小的增量值，这里是-2.需要注意的是最小的增量值-2使用zigzag VLQ  int(此时-2变为3)类型来保存的。接下来对-2 -2 -2 1 1 1 1 分别减去最小的增量值-2,则变为 0,0,0，3,3,3，3 ，最大值3的二进制代码为0000000 0000000  0000000 000000011 有效编码是11两位，那么就使用2个bit来保存所有的增量差, 所以这里用一个byte来保存width 2.对这个序列采用位编码。即依次为00 00 00 11 11 11 11 。增量编码保存值的顺序为：first value(7  zigzag VLQ int 变为14)  minimum  delta(- 2 zigzag VLQ int 变为3)  bitwidth(2  1byte)  bitcode(00 00 00 11 11 11 11) .所以7,5,3,1,2,3,4,5的增量编码为：00001110（14） 00000011（3） 00000010（2）  00000011 111111 共计花费8+8+8+14=38位，比原先的32*8=256位减少很多。

在这里简要说一下zigzag VLQ int，这种编码风格源自于google protobuffer, zigzag主要是应对针对负数的编码，该编码会将有符号整型映射为无符号整型，以便绝对值较小的负数仍然可以有较小的varint编码值，如-1。下面是ZigZag对照表：
Signed Original	Encoded As
0	0
-1	1
1	2
-2	3
2147483647	4294967294
-2147483648	4294967295
      其公式为：
       (n << 1) ^ (n >> 31)    //sint32
      (n << 1> ^ (n >> 63)       //sint64
      需要补充说明的是，Protocol Buffer在实现上述位移操作时均采用的算术位移，因此对于(n >> 31)和(n >> 63)而言，如果n为负值位移后的结果就是-1，否则就是0。


2.其他来源整型编码
----

2.1 位编码
位编码适合于用来存储NULL值，如果值为NULL，则用bit 0表示，否则用bit 1表示不为NULL(此时需结合其他编码)。

2.2 RLE编码
对于int,long,short类型的字段，使用RLE编码。该编码能够对等差数列（完全相等也属于等差数列）进行压缩，该等差数列需要满足以下两个条件：
    1．至少有含3个元素满足等差数列
    2．差值在-128~127之间（因为差值用1Byte来表示）
    Run-Length的具体存储如下：
    第一个Byte是Control Byte，取值在-128~127之间，其中-1~-128代表后面存储着1~128个不满足等差数列的数字，0~127代表后面存储着3~130个满足等差数列的数字；
如果Control Byte>=0，则后面跟着一个Byte存储等差数列的差值，否则跟着-Control Byte这个负数。
如果Control Byte>=0，则继差值之后跟着等差数列的第一个数。
例子：
    原始数字：12,12,12,12,12,10,7,13
    经过Run-Length的数字：2, 0,12,-3,10,7,13
绿色代表Control Byte，蓝色代表差值，黑色代表具体的数字。
细节提示，在RLE编码中，可以用一个byte来存储控制位，一个byte来存储数列的差值，这样可以节省存储空间，毕竟实际情况几乎没有长度到达2的31次方的等差数列，也没有差值达到2的31次方的等差数列。

2.3 variable-width encoding 
基于 Google's protocol buffers ，是使用一个或多个字节表示整型数据的方法（字节是此编码的基本单元）。其中数值本身越小，其所占用的字节数越少. 在varint中，每个字节中都包含一个msb(most significant bit)设置(使用最高位)，这意味着其后的字节是否和当前字节一起来表示同一个整型数值。而字节中的其余七位将用于存储数据本身。如果高位是1则表示后面还有字节来表示同一个整数值，如果高位是0则表示此字节是此数的最后一个字节。通常而言，整数数值都是由字节表示，其中每个字节为8位，即Base 256。然而在Protocol Buffer的编码中，最高位成为了msb，只有后面的7位存储实际的数据，因此我们称其为Base 128（2的7次方）。考虑到此高位的值的含义已经取代了符号位的含义，那么对于负数的编码，需要先使用zigzag编码把负数映射为非负数。这种编码对于小的值的存储比较有效果。

由于Protocol Buffer是按照Little Endian的方式进行数据布局的，因此我们这里需要将两个字节的位置进行翻转
  比如数字1，它本身只占用一个字节即可表示，所以它的msb为其本身的值，如：
   0000 0001
  再比如十进制数字300，它的编码后表示形式为：
  1010 1100 0000 0010
  对于Protocol Buffer而言又是如何将上面的字节布局还原成300呢？这里我们需要做的第一步是drop掉每个字节的msb。从上例中可以看出第一个字节（1010 1100）的msb（最高位）被设置为1，这说明后面的字节将连同该字节表示同一个数值，而第二个字节（0000 0010）的msb为0，因此该字节将为表示该数值的最后一个字节了，后面如果还有其他的字节数据，将表示其他的数据。
   1010 1100 0000 0010
   -> 010 1100 000 0010
   上例中的第二行已经将第一行中每一个字节的msb去除。由于Protocol Buffer是按照Little Endian的方式进行数据布局的，因此我们这里需要将两个字节的位置进行翻转。
   010 1100 000 0010
   -> 000 0010 010 1100           //翻转第一行的两个字节
   -> 100101100                    //将翻转后的两个字节直接连接并去除高位0
  -> 256 + 32 + 8 + 4 = 300    //将上一行的二进制数据换算成十进制，其值为300.

上面的举例是反编码，下面举例如何编码为Protocol Buffer。
先表示具体值----从低位开始以7为模划分，不足的补为0----翻转数据（小端存储）-----添加msb值0 OR 1.
	比如java  int  137 ，其不做编码的二进制表示为0000000  00000000  00000000 10001001 需要4个字节，有效位是 10001001，对其进行处理：
1.	7 bit 一组，把二进制分开，不足的补 0 ，变成 0000001 0001001
翻转数据，0001001  0000001
2. 把最低的7位拿出来，在最高位补0表示最后一位，变成00000001，这个作为最低位，放在最后边。
3. 在其他组的最高位补 1 ，表示没有结束，后面跟着还有数据。在这里就是 10001001
4. 拼在一起，就变成了 10001001  00000001 只需要两个字节就可以存储。 

3  对比归纳
----
编码来源        	编码方式 	  优点    	缺点    	典型应用        	是否发现改进点
Parquet	plain Encoding	基本无	最次的选择	在高效编码方式不适合使用的情况下使用	

Parquet	bit-packing Enconding	 运用位编码节省了存储空间（需要提示一点的是，位编码很适合于非负整数的编码，对于负整数的编码，应为有符号位则较麻烦，后续的集中位编码的应用案列都较好的避开了出现负数的序列的情况）	以java int类型为例（4个字节），如果值为011111111 11111111 11111111 11111111 这种很大的情况下，不能起到节省空间的作用；
另外，此处的RLE只能对22222211111这样的序列进行编码，而对于1234567这样的序列则不行，即不支持等差序列的编码。	存储的值偏小的情况下，性能最优	

Parquet	Dictionary Encoding	 对于重复次数很多的情况很适合	存储字典项ID的时候未考虑使用位编码能节省空间	适合于值重复出现次数较多的情况，但不要求重复出现的值要连续出现	字典内部是否可以支持位编码，或者说是字典内部对值的保存采用Google's protocolbuffers 的 variable-width

Parquet	Run Length Encoding      适合于连续多次出现重复的值的情况，而且内部还采用了位编码，更加节省了存储空间	对于值重复次数虽然多但是却不连续的情况下不但没效果，还可能比plain Encoding占用更大存储空间，并且编码更耗时	适合于值重复出现次数较多的情况，并且要求重复出现的值要连续出现（这样的话，其性能优于不能充分利用连续重复值的Dictionary Encoding）	支持对等差数列的编码

Parquet	Delta  bit packing  Encoding（这里将这两个编码混合在一起，主要是考虑到在代码中这是一个整体部分，而且优势明显，所以就没故意拆分了） 	充分结合了增量编码和位编码的优势，增量编码对于那种不重复出现的随机分布的数值进行编码最合适不过了。鉴于是对int 序列的相对增量进行编码，所以一般就不会受int 值本身大小的影响，一般不会存在前面位编码受较大值的影响的情况。此处需要规避负数的位编码问题，如果序列的增量出现了负数，则需要以序列的增量中的最小负数为基准，为每一个增量值加上此最小负数的绝对值，而规避负数的位编码问题）	对于连续多次出现重复值的情况，可能性能不及RLE编码。对于11111111112222222222这样的序列：RLE编码为：
1 10 2 10 代价为4个字节（如果RLE内部还使用bit-packing的话就更小了）

Delta  bit packing  Encoding 编码则会偏大，究其原因是此编码不能很好利用重复值情况，即使delta是0，那么得用一个zigzag VLQ  INT来保存0，一个byte来保存0，然后每两个相邻值的差值还得用一个bit 0存储。	特别适合于值分布随机无明显规律的情况，但是最好是相邻两个值的增量不要过大，不然会影响位编码的性能	合理的确定一个长度的数组作为基本单元，此基本单元的所有数值的bit长度取决于此数组对负增量修正后的最大增量值，所以要确保delta不要过大。还有就是是否可以让此编码增加对连续重复值的支持。

组合Parquet	 bit-packing Enconding+ Run Length Encoding	结合位编码节省空间以及RLE对连续多次重复值存储的优势。用位编码来保存值重复出现的次数，在一定程度上缓解了位编码受限于存储值大小的影响。比如我存储100个连续的230 效果明显。	不适合于值分布随机或者是即使重复值很多但不连续的情况	适合于连续多次重复值的情况，而且性能更好	
组合Parquet	Dictionary Encoding +bit-packing Enconding+ Run Length	总体上采用字典编码，内部依据字典项id连续出现的情况采用RLE-Bit Packing （连续出现重复次数多）或者bit packing 编码（值分布随机）
考虑到此处的位编码是用于对字典项Id进行编码，id当然是非负数，规避了负数的位编码问题）	关于界定何时使用单纯的位编码还是使用RLE和位编码的混合编码。以及是否可以考虑在字典内部也采用位编码保存，而不仅仅是对字典项id使用位编码	适合于重复值较多的情况（不论是否连续出现），不适合值分布随机的情况	字典内部是否可以支持位编码，或者说是字典内部对值的保存采用Google's protocolbuffers 的 variable-width

ORC	位编码	此处的位编码和parquet的位编码不同，这里仅仅值以bit 0表示值为Null，bit 1表示值为非空。此编码除了可以用于boolean类型存储外，也可以用于整型编码中辅助存储NULL值。	对于整型编码而言只能用作辅助存储，其只能存储NULL值，对于非空值只能用bit 1来作为非空值的标志。

ORC	RLE	   相比于parquet中的只能对重复值进行编码的RLE的情况，增加了对等差数列的编码支持。适合于等差序列的值的编码，即使出现的值是随机分布的，也仅需要一个byte大小的控制位的额外存储。	未能考虑对存储的value的有效位进行编码。		
ORC	variable-width encoding	对于int,long这种本身需要较多字节的类型，但是其值偏小的情况效果更佳。此方法于parquet中的基于相邻value的增量差进行位编码的区别是，Delta  bit -packing  Encoding编码的长度受限于增量差的最大值（Delta  bit -packing  Encoding为一组值编码的位长度是固定的），而variable-width encoding则根据存储的每一个值的具体情况来决定长度。	采用variable-width encoding编码的话，值的长度是byte的整数倍，Delta  bit -packing  Encoding 则不受这个限制，而且variable-width encoding 还会浪费每一个byte的高位来表示前后字节的关系，这在Delta  bit -packing  Encoding中也是不存在的。bit -packing  Encoding 和variable-width encoding同样都存在编码负数的问题，对此Delta  bit -packing  Encoding采用以最小相邻增量值为基准的新增量巧妙避开了负值的问题，而variable-width encoding则是对负数采用 zigzag编码对负数进行映射为非负数。
		
组合ORC	bit coding—RLE coding-- variable-width encoding 	这三种编码的组合编码可以应对NULL值，连续出现等差数列以及采用variable-width encoding 节省存储空间。	没有字典编码对于非连续出现的重复值进行编码的功能。也不适合对byte类型进行编码。所以可以考虑在此基础上加上一个字典编码在外围，内部再依据重复值的分布情况设定一个判断条件决定采用单纯的variable-width encoding或者是位编码或者是bit coding—RLE coding-- variable-width encoding。	有NULL值出现，存在较多的等差数列，存储值的普遍偏小且数值类型非byte型	可以考虑增加字典编码应对对重复值较多但不连续的情况。


以上表格是现在所能得出的总结，我相信还会有更多的发现。

4参考资料
----
1.	https://github.com/Parquet/parquet-format/blob/master/Encodings.md#run-length-encoding--bit-packing-hybrid-rle--3
2.	http://docs.hortonworks.com/HDPDocuments/HDP2/HDP-2.0.0.2/ds_Hive/orcfile.html
3.	http://code.google.com/p/protobuf/
4.	http://lemire.me/blog/archives/2012/03/06/how-fast-is-bit-packing/
5.	http://lemire.me/blog/archives/2012/04/05/bit-packing-is-fast-but-integer-logarithm-is-slow/
6.	http://lemire.me/blog/archives/2012/10/23/when-is-a-bitmap-faster-than-an-integer-list/
7.	http://graphics.stanford.edu/~seander/bithacks.html#IntegerLog
8.	http://lemire.me/blog/archives/2009/11/13/more-database-compression-means-more-speed-right/
9.	http://lemire.me/blog/archives/2012/02/08/effective-compression-using-frame-of-reference-and-delta-coding/
10.	http://en.wikipedia.org/wiki/Vectorization_(parallel_computing)
11.	http://lemire.me/blog/archives/2012/09/12/fast-integer-compression-decoding-billions-of-integers-per-second/

