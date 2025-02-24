0N!"Parquet file reading examples";

l:`$":libPQ"
file:`$"test.parquet"
o:.Q.def[`init`file`libfile!(1b;file;l);.Q.opt[.z.x]]
`. upsert o;

//Load library from default location
`.pq upsert (l 2:(`getparquetlib;1))[];

//Load library from default location
`.pq upsert (l 2:(`getparquetlib;1))[];


//Create a simple table
tab:([]j:1 2 3;f:3 4 5.;d:.z.d;s:`a`b`c)

initpq:{[x]
   @[hdel;hsym file;0b];
   -1 "============================================";
   -1 "Saving sample table: ",s:".pq.settabletofile[file;tab]"; 
   show value s;
   -1 "Reading sample table: ",s:".pq.getfile[file]";
   show value s;
   -1 "Inspecting sample table: ",s:".pq.getschema[file]";
   show value s;
   -1 "Reading subset of columns from file: ",s:".pq.getfilebycols[file;`j`f`d]";
   show value s;
   -1 "============================================";
   -1 " Good bye ";
    exit[0];  
     }


if[init;initpq[]];
