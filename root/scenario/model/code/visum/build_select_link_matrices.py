#build select link matrices across demand segments for the links specified in file

from __future__ import with_statement

##################################################
#build_select_link_matrices.py
# Calculates the select link matrices for a subarea. The subarea is defined by a
#   series of links which intersect the subarea. All of the links intersecting the
#   boundary must be specified, or the process will not return consistent/correct
#   results. The links are listed in a select link file which is a csv file with
#   the columns:
#
#      FROMNODE,TONODE,DIRECTION,STATIONNUMBER
#
#   where FROMNODE/TONODE are the start/end nodes of the link, DIRECTION is either
#   OUT or IN and indicates whether the link is entering or exiting the subarea
#   (both directions must be specified separately, if they exist), and STATIONNUMBER
#   is a user-defined number for the station. The station number must be distinct
#   from the zone numbers, as it will be used as an identifier in the output matrices.
#
# This script will output the demand matrices as csv files with the columns:
#
#      ASSIGNCLASS,FROMNODETONODE,DIRECTION,FROMZONE,TOZONE,PERCENT,STATIONNUMBER
#
#   where ASSIGNCLASS is the demand segment, FROMNODETONODE is the link nodes in the format
#   'from to', DIRECTION and STATIONNUMBER are from the select link file, FROMZONE/TOZONE
#   are the trip od zone pairs, and PERCENT is the percent of trips between FROMZONE and
#   TOZONE that use the particular link.
#
# This script is called as:
#
#      python build_select_link_matrices.py version_file peak_paths_version_file offpeak_paths_version_file select_link_file output_file dsegs
#
#   where the files must be the full (absolute) paths, and dsegs is a valid python
#   dictionary from demand segment names to their matrix index numbers (in the VISUM version
#   file. e.g. {'a':1,'e':3}
#
# crf - 4/2013
##################################################

import os,csv,sys,gc,time,threading,datetime
import pythoncom
import win32com.client as com
import VisumPy.helpers
import numpy as np
import shutil
import ast
from Properties import Properties

if len(sys.argv) < 6:
    print "Missing arguments! Usage:\n\t" + "python build_select_link_matrices.py version_file peak_paths_version_file offpeak_paths_version_file select_link_file output_file dsegs"
    sys.exit(1)

#load properties
property_file = os.path.dirname(sys.argv[1]) + '/si.properties'
properties = Properties()
properties.loadPropertyFile(property_file)
programVersion = properties['visum.version']
addDemandMatrices = ast.literal_eval(properties['sl.add.demand.matrices'].capitalize()) #capitalize first letter to make sure that True or False strings are in the format expected by python
main_version_file = sys.argv[1]
peak_path_version_file = sys.argv[2]
offpeak_path_version_file = sys.argv[3]
select_link_file = sys.argv[4]
output_file = os.path.join(os.path.dirname(main_version_file),sys.argv[5])
dsegs = eval(sys.argv[6])
fb_matrix = os.path.join(os.path.dirname(main_version_file),'flow_bundle_temp.mtx')
matrix_index_next = 21 #index of the next matrix in the main version file - used to add SL and non-SL demand matrices

#collect select link data
ft_nodes = []
with open(select_link_file,'rb') as f:
    reader = csv.DictReader(f)
    for row in reader:
        ft_nodes.append(row)

def copyVersion(infile):
    #copy main version file
    newfile = str(main_version_file).replace(".ver", "_SL.ver")
    shutil.copy(infile,newfile)
    return(newfile)

def loadVersion(version_file):
    #Start Visum and load file
    Visum = com.Dispatch('visum.visum.'+programVersion)
    Visum.LoadVersion(version_file)
    return Visum
    
Visum = loadVersion(main_version_file)

#make a copy of the main version file, if SL demand matrices need to be added
if(addDemandMatrices):
    main_version_file_sl = copyVersion(main_version_file)

#get total trips by model/time period
total_trips = {}
for dseg in dsegs:
    total_trips[dseg] = VisumPy.helpers.GetODMatrix(Visum,dsegs[dseg])

#get zone indices
zones = VisumPy.helpers.GetMulti(Visum.Net.Zones,'No')
zone_indices = {}
for i in range(len(zones)):
    zone_indices[zones[i]] = i

del Visum
gc.collect()

class FbThread(threading.Thread):
    def __init__(self,dseg,ft_nodes,index,writer,matrix_index_next):
        threading.Thread.__init__(self)
        self.dseg = dseg
        self.ft_nodes = ft_nodes
        self.index = index
        self.writer = writer
        self.matrix_index_next = matrix_index_next
        self.normal = True
    
    def kill(self):
        self.normal = False
        #get exes
        exe_list = []
        for task in [str.split(x,',') for x in os.popen('tasklist /FO csv').read().replace('"',"").split('\n')][1:]:
            if task[0].startswith('Visum'):
                exe_list.append(task[0])
        for exe in exe_list:
            os.popen('taskkill /F /IM "' + exe + '"')
        
    def run(self):
        try: 
            pythoncom.CoInitialize()

            if self.dseg.find('offpeak') > -1:
                v_file = offpeak_path_version_file
            else:
                v_file = peak_path_version_file

            self.Visum = loadVersion(v_file)

            for ft_node in self.ft_nodes[self.index:]:
                print "dseg: " + self.dseg + "   ftnode: " + str(ft_node)
                netElem = self.Visum.CreateNetElements()
                fnode = int(ft_node['FROMNODE'])
                tnode = int(ft_node['TONODE'])
                mode = 0 # 0 means link, 1 means origin connector, -1 means destination connector
                try:
                    link = self.Visum.Net.Links.ItemByKey(fnode,tnode)
                except:
                    link = None
                if link is None:
                    mode = 1
                    try:
                        link = self.Visum.Net.Connectors.SourceItemByKey(fnode,tnode)
                    except:
                        link = None
                if link is None:
                    mode = -1
                    link = self.Visum.Net.Connectors.DestItemByKey(fnode,tnode)
                
                fb = self.Visum.Net.DemandSegments.ItemByKey(self.dseg).FlowBundle
                fb.Clear()
                
                if mode == 0:    
                    netElem.Add(link)
                    fb.Execute(netElem)
                elif mode == 1:
                    ozone = self.Visum.Net.Zones.ItemByKey(fnode)
                    activity_type_z = fb.CreateActivityTypeSet()
                    activity_type_z.Add(1)
                    fb.CreateCondition(ozone,activity_type_z)
                    node = self.Visum.Net.Nodes.ItemByKey(tnode)
                    activity_type_n = fb.CreateActivityTypeSet()
                    activity_type_n.Add(0)
                    fb.CreateCondition(node,activity_type_n)
                    fb.ExecuteCurrentConditions()
                elif mode == -1:
                    node = self.Visum.Net.Nodes.ItemByKey(fnode)
                    activity_type_n = fb.CreateActivityTypeSet()
                    activity_type_n.Add(0)
                    fb.CreateCondition(node,activity_type_n)
                    
                    dzone = self.Visum.Net.Zones.ItemByKey(tnode)
                    activity_type_z = fb.CreateActivityTypeSet()
                    activity_type_z.Add(2)
                    fb.CreateCondition(dzone,activity_type_z)
                    fb.ExecuteCurrentConditions()

                mtx = fb.GetOrCreateFlowBundleMatrix(self.dseg)  # flow bundle as an IMatrix object
                mtx.Save(fb_matrix,'O')
                fb.Clear()
                del fb
                del netElem
                gc.collect()

                if(addDemandMatrices):
                    #initialize an array
                    matrix_sl = np.zeros((len(zones),len(zones)))
                
                if self.normal:
                    row = {}
                    row['ASSIGNCLASS'] = dseg
                    row['FROMNODETONODE'] = ft_node['FROMNODE'] + ' ' +  ft_node['TONODE']
                    row['DIRECTION'] = ft_node['DIRECTION']
                    row['STATIONNUMBER'] = ft_node['STATIONNUMBER']
                    trips = total_trips[self.dseg]
                    header = True
                    date_today = datetime.datetime.now().strftime("%m/%d/%y")
                    header_end = '* ' + date_today # format example: '* 05/31/16'
                    print "  reading in fb matrix data"
                    line_count = 0
                    for line in open(fb_matrix,'rb'):
                        #have to skip header junk
                        if header:
                            if line.startswith(header_end):
                                header = False
                            continue
                        if len(line.strip()) == 0 or line[0] == '*':
                            continue
                        data = line.split()
                        if data[0] == '$NAMES':
                            break
                        from_zone = int(data[0])
                        to_zone = int(data[1])
                        
                        if(addDemandMatrices):
                            matrix_sl[zone_indices[from_zone],zone_indices[to_zone]] = float(data[2]) #set demand for the OD pair
                        
                        ttrips = trips[zone_indices[from_zone]][zone_indices[to_zone]]
                        if ttrips > 0:
                            row['FROMZONE'] = from_zone
                            row['TOZONE'] = to_zone
                            row['PERCENT'] = min(1.0,float(data[2]) / ttrips)
                            self.writer.writerow(row)
                        line_count += 1
                        if line_count % 100 == 0:
                            print "  processed " + str(line_count) + " lines"

                if (addDemandMatrices):
                    #total SL demand
                    if self.index==0:
                        matrix_dseg_sl = matrix_sl
                    else:
                        matrix_dseg_sl += matrix_sl

                self.index += 1
            
            del self.Visum

            if (addDemandMatrices):
                print("adding SL demand matrix to main version file: " + self.dseg)
                Visum = loadVersion(main_version_file_sl)
                
                #add SL demand
                Visum.Net.AddODMatrix(self.matrix_index_next)
                Visum.Net.Matrices.ItemByKey(self.matrix_index_next).SetAttValue("Name", "sl_"+self.dseg)
                VisumPy.helpers.SetODMatrix(Visum, self.matrix_index_next, matrix_dseg_sl)

                #add remaining demand
                print("adding remaining demand matrix to main version file: " + self.dseg)
                matrix_dseg_rem = np.asarray(total_trips[self.dseg]) - matrix_dseg_sl
                self.matrix_index_next += 1
                Visum.Net.AddODMatrix(self.matrix_index_next)
                Visum.Net.Matrices.ItemByKey(self.matrix_index_next).SetAttValue("Name", "rem_"+self.dseg)
                VisumPy.helpers.SetODMatrix(Visum, self.matrix_index_next, matrix_dseg_rem)

                #save edits and close the version file
                Visum.SaveVersion(main_version_file_sl)
                del Visum

        except:
            if self.normal:
                raise 

with open(output_file,'wb') as f:
    writer = csv.DictWriter(f,['ASSIGNCLASS','FROMNODETONODE','DIRECTION','FROMZONE','TOZONE','PERCENT','STATIONNUMBER'])
    writer.writer.writerow(writer.fieldnames)

    for dseg in dsegs:
        index = 0
        while True:
            print '  trying ' + str(index)
            
            fbt = FbThread(dseg,ft_nodes,index,writer,matrix_index_next)
            matrix_index_next += 2
            
            fbt.start()
            
            while True: #wait loop
                fbt.join(90)
                if ((not fbt.isAlive()) or (index == fbt.index)):
                    break
                index = fbt.index
            alive = fbt.isAlive()
            if alive:
                index = fbt.index
                fbt.kill()
            del fbt
            if not alive:
                break

if os.path.exists(fb_matrix):
    os.remove(fb_matrix)
