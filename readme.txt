------------------------------
| Project structure overview |
------------------------------

metais-scripts/
├── groovy/
│   ├── curated/                <- Curated report scripts (predefined columns)
│   │   ├── AS.groovy
│   │   ├── INFRA.groovy
│   │   └── ...
│   ├── misc/
│   │   ├── all_enums.groovy
│   │   └── enum_names.json
│   ├── templates/              <- Skeleton scripts for new report generation
│       ├── extract_raw_template.groovy
│       └── extract_relation_template.groovy
│
├── params/
│   └── params.json             <- Default metadata & query configuration
│
├── py/
│   ├── raw_reports.py          <- Batch extract selected raw datasets
│   ├── raw_relations.py        <- Batch extract all relations for a dataset
│   └── unique_attributes.py    <- Analyze attributes & frequencies in raw data
│
├── run/
│   ├── convert.sh              <- JSON → CSV converter
│   ├── core.sh                 <- Backend for selecting report type
│   ├── lib.sh                  <- Shared bash utilities
│   ├── raw.sh                  <- Extract raw dataset
│   ├── relation.sh             <- Extract relation tables
│   └── run.sh                  <- Main entrypoint script
│
└── output/                     <- All results saved here


------------------------------------
| Important Notes Before You Start |
------------------------------------

- Always run commands from the root folder metais-scripts/
- All outputs (.json, .csv, logs) are saved to output/.
- A valid TOKEN environment variable (MetaIS API bearer token) must be exported.
    $ export TOKEN="your-Bearer-token-here"


---------------
| Basic Usage |
---------------

Show help
    $ run/run.sh -h

-------------------------------------------------------
| Run Curated Reports (pre-defined columns and logic) |
-------------------------------------------------------

Curated scripts are located in groovy/curated/.

Example:
    $ run/run.sh -s groovy/curated/KS.groovy

This generates output/KS.json (and automatically CSV).


-----------------------
| Extract Raw Dataset |
-----------------------

Raw export of a central dataset (no filtering, full attributes).
    $ run/raw.sh KS

Produces: output/KS_raw.json


--------------------------------------
| Batch Export Multiple Raw Datasets |
--------------------------------------

A few select datasets can be obtained by running
    $ python3 py/raw_reports.py

- Runs several raw exports (predefined inside the script).
- Retries automatically in case of timeout/connection problems.
- All outputs go to output/.


--------------------------------
| Query Enum / Codelist Values |
--------------------------------

Enums = internal MetaIS codelists (typ_ks, typ_as, etc.)
    $ run/run.sh -s groovy/misc/all_enums.groovy --params groovy/misc/enum_names.json

Outputs output/enums.json with values for all known enums. Add more enum names to groovy/misc/enum_names.json upon discovery to keep the list updated.


----------------------------
| Extract a Relation Table |
----------------------------

Format: central_dataset related_dataset relation_name
    $ run/relation.sh KS PO je_gestor

Optional full relation name for irregular naming (the third parameter is purely formal in this case):
    $ run/relation.sh Projekt Projekt asociuje Projekt_je_asociovany_s_projektom


-----------------------------------------
| Extract ALL Relations for One Dataset |
-----------------------------------------

Run
    $ python3 py/raw_relations.py KS

- Generates a set of output/*_rel_*.json files.
- Relations included are defined inside the Python file → you can tweak them.


----------------------------------------
| Inspect Attributes & Their Frequency |
----------------------------------------

Run
    $ python3 py/unique_attributes.py KS_raw

Shows attributes sorted by usage percentage (most frequent → rarest).

Optional: include attributes from related datasets:
    $ python3 py/unique_attributes.py KS_raw PO_je_gestor_KS,PO_raw Projekt_realizuje_KS,Projekt_raw


---------------------------------
| Extras You Might Want to Know |
---------------------------------

Feature                             Description
--------------------------------------------------------------
JSON → CSV conversion               Automatically done by run.sh, or manually
                                    with run/convert.sh input.json output.csv

Retry logic                         Both Python scripts use retry loops for unstable API responses

Creating new report scripts         Mimic one of the curated datasets

Parameters override                 use --params params/custom_params_file.json to pass custom json payload

Logging                             Script outputs progress and errors to console;
                                    consider redirecting to a logfile if needed


-------------------------------
| Common Examples Cheat Sheet |
-------------------------------

Task                            Command
Get Koncové Služby curated      run/run.sh -s groovy/curated/KS.groovy
Raw export of KS                run/raw.sh KS
Enums (all)                     run/run.sh -s groovy/misc/all_enums.groovy --params groovy/misc/enum_names.json
Single relation KS to PO        run/relation.sh KS PO je_gestor
All KS relations                python3 py/raw_relations.py KS
Attribute frequency stats       python3 py/unique_attributes.py KS_raw
JSON → CSV manually             run/convert.sh output/KS_raw.json output/KS_raw.csv


-----------------------------
| Future Possible Additions |
-----------------------------

- Support for incremental updates (only fetch changed data).

- Alternate authentication if TOKEN missing or expired

- Automated CSV post-processing (cleaning null values, flattening nested attributes).

- automated data validation and quality check: compare formal relations against dataset parameters