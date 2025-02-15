# ETL When Loading

When importing data into StarRocks tables, sometimes the content of the target table is **not** exactly the same as the content of the data source. For example:

* Scenario 1: The data source contains some content that is not needed, for example **redundant rows** or **redundant columns**.
* Scenario 2: The content in the data source is not directly imported into StarRocks, and may **require some transformation** work done before or during the imports. For example, the data in the original file is in timestamp format, whereas the data type of the target table is Datetime. In this case, the type conversion needs to be completed during the data import.

StarRocks can perform data transformation while executing data import. This way, in case of inconsistency between the content of the data source and the target table, users can complete the data transformation directly without external ETL work.

With the capabilities provided by StarRocks, users can achieve the following during data import.

1. **Select the columns that need to be imported.** On one hand, this function allows you to skip the columns that do not need to be imported; on the other hand, when the order of the columns in the table does not match the order of the fields in the file, you can use this function to establish a field mapping between the two tables.
2. **Filter unwanted rows.** It is possible to skip rows that do not need to be imported by specifying expressions during import.
3. **Derived columns** (i.e., new columns generated by computational processing) can be generated and imported into the StarRocks target table.
4. **Support Hive partition path naming.** StarRocks can get the content of partition columns from the file path.

---

## Select the Columns to be Imported

### Sample Data

Suppose you need to import a piece of data into the following table:

~~~sql
CREATE TABLE event (
    `event_date` DATE,
    `event_type` TINYINT,
    `user_id` BIGINT
)
DISTRIBUTED BY HASH(user_id) BUCKETS 3;
~~~

However, the data file contains `user_id, user_gender, event_date, event_type` and the sample data is shown below.

~~~text
354,female,2020-05-20,1
465,male,2020-05-21,2
576,female,2020-05-22,1
687,male,2020-05-23,2
~~~

### Local File Import

The following command enables importing  local data into the corresponding table.

~~~bash
curl --location-trusted -u root -H "column_separator:," \
    -H "columns: user_id, user_gender, event_date, event_type" -T load-columns.txt \
    http://{FE_HOST}:{FE_HTTP_PORT}/api/test/event/_stream_load
~~~

The columns in CSV format files are originally unnamed. By setting `columns` you can name them in order (in some CSVs, the column names are given in the first line, but in fact the system is not aware of this and will treat it as normal data). In this case, the `columns` parameter describes the column names **in order**, that is`user_id, user_gender, event_date, event_type`. The data will be imported accordingly.

* Columns with the same name as those in the imported table are imported directly
* Columns that do not exist in the imported table will be ignored during the import
* Columns that exist in the import table but are not specified in `columns` are reported as errors

For this example, `user_id, event_date, event_type` can be found in the table, so the corresponding content will be imported into the StarRocks table. `user_gender` does not exist in the table, so it will be ignored during the import.

### HDFS Import

HDFS data can be imported into the corresponding table by using the following command:

~~~sql
LOAD LABEL test.label_load (
    DATA INFILE("hdfs://{HDFS_HOST}:{HDFS_PORT}/tmp/zc/starrocks/data/date=*/*")
    INTO TABLE `event`
    COLUMNS TERMINATED BY ","
    FORMAT AS "csv"
    (user_id, user_gender, event_date, event_type)
)
WITH BROKER hdfs;
~~~

Columns can be specified by `user_id, user_gender, event_date, event_type`. The process of StarRocks import is the same as the local file import. Required columns will be imported into StarRocks and nonrequired columns will be ignored.

### Kafka Import

The following command enables importing data from Kafka:

~~~sql
CREATE ROUTINE LOAD test.event_load ON event
    COLUMNS TERMINATED BY ",",
    COLUMNS(user_id, user_gender, event_date, event_type),
WHERE event_type = 1
FROM KAFKA (
    "kafka_broker_list" = "{KAFKA_BROKER_HOST}:{KAFKA_BROKER_PORT}",
    "kafka_topic" = "event"
);
~~~

`COLUMNS(user_id, user_gender, event_date, event_type)` can be used to indicate the fields in the Kafka stream message. The process of StarRocks import is the same as the local file import. Required columns will be imported into StarRocks and nonrequired columns will be ignored.

### Query

~~~SQL
> select * from event;
+------------+------------+---------+
| event_date | event_type | user_id |
+------------+------------+---------+
| 2020-05-22 |          1 |     576 |
| 2020-05-20 |          1 |     354 |
| 2020-05-21 |          2 |     465 |
| 2020-05-23 |          2 |     687 |
+------------+------------+---------+
~~~

---

## Skip Rows That Do Not Need to be Imported

### Sample Data

Suppose you need to import a data duplicate into the following table.

~~~sql
CREATE TABLE event (
    `event_date` DATE,
    `event_type` TINYINT,
    `user_id` BIGINT
)
DISTRIBUTED BY HASH(user_id) BUCKETS 3;
~~~

Assuming that the data file contains three columns, the sample data is shown below:

~~~text
2020-05-20,1,354
2020-05-21,2,465
2020-05-22,1,576
2020-05-23,2,687
~~~

Noted that only the data with ***event_type*** of 1 needs to be analyzed in the destination table, then:

### Local File Import

When importing local files, data can be filtered by specifying the Header `where:event_type=1` in the HTTP request.

~~~bash
curl --location-trusted -u root -H "column_separator:," \
    -H "where:event_type=1" -T load-rows.txt \
    http://{FE_HOST}:{FE_HTTP_PORT}/test/event/_stream_load
~~~

### HDFS Import

With the following command, data can be imported with `event_type=1` by the "WHERE event_type = 1" condition.

~~~sql
LOAD LABEL test.label_load (
    DATA INFILE("hdfs://{HDFS_HOST}:{HDFS_PORT}/tmp/zc/starrocks/data/date=*/*")
    INTO TABLE `event`
    COLUMNS TERMINATED BY ","
    FORMAT AS "csv"
    WHERE event_type = 1
)
WITH BROKER hdfs;
~~~

### Kafka Import

With the following command, data can be imported with `event_type=1` by the "WHERE event_type = 1" condition.

~~~sql
CREATE ROUTINE LOAD test.event_load ON event
COLUMNS TERMINATED BY ",",
WHERE event_type = 1
FROM KAFKA (
    "kafka_broker_list" = "{KAFKA_BROKER_HOST}:{KAFKA_BROKER_PORT}",
    "kafka_topic" = "event"
);
~~~

### Query

~~~SQL
> select * from event;
+------------+------------+---------+
| event_date | event_type | user_id |
+------------+------------+---------+
| 2020-05-20 |          1 |     354 |
| 2020-05-22 |          1 |     576 |
+------------+------------+---------+
~~~

---

## Generating Derived Columns

Suppose you need to import a data duplicate into the following table.

~~~sql
CREATE TABLE dim_date (
    `date` DATE,
    `year` INT,
    `month` TINYINT,
    `day` TINYINT
)
DISTRIBUTED BY HASH(date) BUCKETS 1;
~~~

However, the original data file contains only one column:

~~~text
2020-05-20
2020-05-21
2020-05-22
2020-05-23
~~~

When importing, the data is transformed by the following command.

### Local File Import

With the following command, StarRocks can generate the corresponding derived columns while importing local files with `Header "columns:date, year=year(date), month=month(date), day=day(date)"` in the HTTP request.

This allows StarRocks to calculate and generate the corresponding columns based on the file content being imported.

~~~bash
curl --location-trusted -u root -H "column_separator:," \
    -H "columns:date,year=year(date),month=month(date),day=day(date)" -T load-date.txt \
    http://127.0.0.1:8431/api/test/dim_date/_stream_load
~~~

Note:

* First, you need to list all the columns in the CSV format file followed by the derived columns;
*
* Don’t use `col_name = func(col_name)`. Rename the column name, e.g. `col_name = func(col_name0)`.

### HDFS Import

Similar to the aforementioned local file import, HDFS file import is possible with the following command.

~~~sql
LOAD LABEL test.label_load (
    DATA INFILE("hdfs://{HDFS_HOST}:{HDFS_PORT}/tmp/zc/starrocks/data/date=*/*")
    INTO TABLE `event`
    COLUMNS TERMINATED BY ","
    FORMAT AS "csv"
    (date)
    SET(year=year(date), month=month(date), day=day(date))
)
WITH BROKER hdfs;
~~~

### Kafka Import

Similarly, importing the corresponding data from Kafka can be achieved with the following command.

~~~sql
CREATE ROUTINE LOAD test.event_load ON event
    COLUMNS TERMINATED BY ",",
    COLUMNS(date,year=year(date),month=month(date),day=day(date))
FROM KAFKA (
    "kafka_broker_list" = "{KAFKA_BROKER_HOST}:{KAFKA_BROKER_PORT}",
    "kafka_topic" = "event"
);
~~~

### Query

~~~SQL
> SELECT * FROM dim_date;
+------------+------+-------+------+
| date       | year | month | day  |
+------------+------+-------+------+
| 2020-05-20 | 2020 |  5    | 20   |
| 2020-05-21 | 2020 |  5    | 21   |
| 2020-05-22 | 2020 |  5    | 22   |
| 2020-05-23 | 2020 |  5    | 23   |
+------------+------+-------+------+
~~~

---

## Get the Column Content from the File Path

### Sample Data

Suppose we want to import data into the following table.

~~~sql
CREATE TABLE event (
    `event_date` DATE,
    `event_type` TINYINT,
    `user_id` BIGINT
)
DISTRIBUTED BY HASH(user_id) BUCKETS 3;
~~~

The data to be imported is the data generated by Hive, which is partitioned by `event_date`. Each file contains only two columns – `event_type` and `user_id`. The specific data content is shown below.

~~~text
/tmp/starrocks/data/date=2020-05-20/data
1,354
/tmp/starrocks/data/date=2020-05-21/data
2,465
/tmp/starrocks/data/date=2020-05-22/data
1,576
/tmp/starrocks/data/date=2020-05-23/data
2,687
~~~

The following command imports the data into the "event" table and gets "**event_date**" from the file path.

### HDFS Import

~~~SQL
LOAD LABEL test.label_load (
    DATA INFILE("hdfs://{HDFS_HOST}:{HDFS_PORT}/tmp/starrocks/data/date=*/*")
    INTO TABLE `event`
    COLUMNS TERMINATED BY ","
    FORMAT AS "csv"
    (event_type, user_id)
    COLUMNS FROM PATH AS (date)
    SET(event_date = date)
)
WITH BROKER hdfs;
~~~

The above command imports all files matching the path wildcard into the "event" table. The files are in CSV format, and the columns are split by `,`. The file contains two columns – "event_type" and "user_id". We can **get the  "date" column from the file path** because the corresponding name of the date column in the table is "**event_date**". So the mapping is done by the `SET` statement.

### Query

~~~SQL
> select * from event;
+------------+------------+---------+
| event_date | event_type | user_id |
+------------+------------+---------+
| 2020-05-22 |          1 |     576 |
| 2020-05-20 |          1 |     354 |
| 2020-05-21 |          2 |     465 |
| 2020-05-23 |          2 |     687 |
+------------+------------+---------+
~~~
